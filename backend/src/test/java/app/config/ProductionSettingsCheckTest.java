package app.config;

import app.Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The prod profile must refuse to start with development settings (issue #49).
class ProductionSettingsCheckTest {

    @Test
    void theProdProfileFailsToStartWithDevelopmentSettings() {
        Exception exception = assertThrows(Exception.class, () -> new SpringApplicationBuilder(Application.class)
                .profiles("prod")
                .properties("server.port=0")
                // As an environment variable would: it overrides the profile's own setting.
                .run("--spring.h2.console.enabled=true").close());

        String message = rootMessage(exception);
        assertTrue(message.contains("spring.h2.console.enabled must be false"), message);
        assertTrue(message.contains("The database is H2 in memory"), message);
        assertTrue(message.contains("MAIL_HOST is not set"), message);
        assertTrue(message.contains("APP_BASE_URL must be the site's https:// address"), message);
    }

    @Test
    void productionSettingsPass() {
        assertEquals(List.of(), new ProductionSettingsCheck(secure()).problems());
    }

    @Test
    void eachInsecureSettingIsNamed() {
        MockEnvironment environment = secure()
                .withProperty("server.servlet.session.cookie.secure", "false")
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("app.cors.allowed-origins", "https://chicky.example, http://localhost:5500");

        List<String> problems = new ProductionSettingsCheck(environment).problems();

        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(2).contains("not http://localhost:5500"), problems.toString());
    }

    private static MockEnvironment secure() {
        return new MockEnvironment()
                .withProperty("spring.h2.console.enabled", "false")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.forward-headers-strategy", "framework")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example:5432/postgres?sslmode=require")
                .withProperty("spring.mail.host", "smtp-relay.example")
                .withProperty("app.base-url", "https://chicky.example")
                .withProperty("app.cors.allowed-origins", "https://chicky.example");
    }

    private static String rootMessage(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
