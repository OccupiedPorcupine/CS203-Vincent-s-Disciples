package app.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// With the prod profile, refuses to start on settings that are fine for development but unsafe
// with real users' accounts and sales: it's better for a deployment to fail than to run like that.
@Component
@Profile("prod")
public class ProductionSettingsCheck implements InitializingBean {

    private final Environment environment;

    public ProductionSettingsCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Insecure settings for the prod profile:\n- " + String.join("\n- ", problems));
        }
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (environment.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            problems.add("spring.h2.console.enabled must be false: the H2 console would expose the database.");
        }
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add("server.servlet.session.cookie.secure must be true, so the login cookie is only sent over HTTPS.");
        }
        if ("none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"))) {
            problems.add("FORWARD_HEADERS_STRATEGY must be framework behind Vercel's proxy, or every user shares one IP "
                    + "address for rate limits and requests don't count as HTTPS.");
        }
        if (environment.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:")) {
            problems.add("The database is H2 in memory, which loses everything on restart. Use the supabase profile.");
        }
        if (environment.getProperty("spring.mail.host", "").isBlank()) {
            problems.add("MAIL_HOST is not set, so password reset and verification links would be written to the log.");
        }
        if (!environment.getProperty("app.base-url", "").startsWith("https://")) {
            problems.add("APP_BASE_URL must be the site's https:// address; emailed links point there.");
        }
        Arrays.stream(environment.getProperty("app.cors.allowed-origins", "").split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty() && !origin.startsWith("https://"))
                .forEach(origin -> problems.add("APP_CORS_ALLOWED_ORIGINS must only list https:// origins, not " + origin + "."));
        return problems;
    }
}
