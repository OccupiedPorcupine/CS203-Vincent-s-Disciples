package app.config;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Checks the login cookie the browser actually receives carries the settings in
// src/main/resources/application.properties (and application-prod.properties).
// The test application.properties replaces the main one, so those settings are not loaded in tests
// by default: these tests copy just the server.servlet.session.cookie.* lines across, so editing
// them in the real config file makes these tests fail.
class SessionCookieConfigApiTest {

    private static final String COOKIE_SETTINGS = "server.servlet.session.cookie.";

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class DefaultSettings extends CookieChecks {

        @DynamicPropertySource
        static void cookieSettings(DynamicPropertyRegistry registry) {
            copyCookieSettings(registry, "src/main/resources/application.properties");
        }

        @Test
        void theLoginCookieCannotBeReadByScripts() throws Exception {
            assertTrue(attributes().contains("httponly"), sessionCookie);
        }

        @Test
        void theLoginCookieIsNotSentWithCrossSiteRequests() throws Exception {
            assertTrue(attributes().contains("samesite=lax"), sessionCookie);
        }

        @Test
        void theLoginCookieStillWorksOverPlainHttpLocally() throws Exception {
            // SESSION_COOKIE_SECURE defaults to false so http://localhost development keeps working.
            assertFalse(attributes().contains("secure"), sessionCookie);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class ProductionSettings extends CookieChecks {

        @DynamicPropertySource
        static void cookieSettings(DynamicPropertyRegistry registry) {
            copyCookieSettings(registry,
                    "src/main/resources/application.properties", "src/main/resources/application-prod.properties");
        }

        @Test
        void theLoginCookieIsOnlySentOverHttps() throws Exception {
            assertTrue(attributes().contains("secure"), sessionCookie);
        }

        @Test
        void theLoginCookieKeepsItsOtherProtections() throws Exception {
            assertTrue(attributes().contains("httponly"), sessionCookie);
            assertTrue(attributes().contains("samesite=lax"), sessionCookie);
        }
    }

    // Later files override earlier ones, like a Spring profile file overrides application.properties.
    // Paths are relative to backend/, where Maven runs the tests.
    private static void copyCookieSettings(DynamicPropertyRegistry registry, String... files) {
        Properties merged = new Properties();
        for (String file : files) {
            try {
                Properties properties = PropertiesLoaderUtils.loadProperties(new FileSystemResource(file));
                assertFalse(properties.isEmpty(), "Couldn't read " + file);
                merged.putAll(properties);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        merged.stringPropertyNames().stream()
                .filter(name -> name.startsWith(COOKIE_SETTINGS))
                .forEach(name -> registry.add(name, () -> merged.getProperty(name)));
    }

    abstract static class CookieChecks {

        @MockitoBean
        PublicHolidaySyncService publicHolidaySyncService;

        @LocalServerPort
        int port;

        String sessionCookie;

        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        // Signs up over real HTTP and returns the attributes of the SESSION cookie it was given,
        // lower-cased and without spaces (e.g. "path=/", "httponly", "samesite=lax").
        List<String> attributes() throws Exception {
            String email = "cookie-" + UUID.randomUUID() + "@example.com";
            HttpResponse<String> signup = post("/api/auth/signup", csrfToken(),
                    "{\"email\":\"" + email + "\",\"password\":\"a-long-unique-password\",\"name\":\"Cookie test\"}");
            assertEquals(201, signup.statusCode(), signup.body());

            sessionCookie = signup.headers().allValues("Set-Cookie").stream()
                    .filter(header -> header.startsWith("SESSION="))
                    .reduce((first, last) -> last)
                    .orElseThrow(() -> new AssertionError("No SESSION cookie in " + signup.headers().map()));
            return Arrays.stream(sessionCookie.split(";"))
                    .skip(1)
                    .map(attribute -> attribute.replace(" ", "").toLowerCase())
                    .toList();
        }

        private String csrfToken() throws Exception {
            String body = http.send(HttpRequest.newBuilder(uri("/api/csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            assertTrue(matcher.find(), body);
            return matcher.group(1);
        }

        private HttpResponse<String> post(String path, String csrfToken, String json) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", csrfToken)
                            .POST(HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
