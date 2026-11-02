package app.config;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Issue #16: the backend must start without a Google client ID, tell the login page to hide the
// Google button, refuse Google logins with a clear error, and keep email/password login working.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "google.client-id=")
class GoogleSignInOptionalTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @Test
    void configReturnsAnEmptyClientId() throws Exception {
        HttpResponse<String> config = http.send(
                HttpRequest.newBuilder(uri("/api/config")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, config.statusCode());
        assertTrue(config.body().matches(".*\"googleClientId\"\\s*:\\s*\"\".*"), config.body());
    }

    @Test
    void googleLoginExplainsThatItIsNotConfigured() throws Exception {
        HttpResponse<String> login = post("/api/auth/google", csrfToken(), "{\"credential\":\"some-google-token\"}");

        assertEquals(503, login.statusCode(), login.body());
        assertTrue(login.body().contains("GOOGLE_SIGN_IN_NOT_CONFIGURED"), login.body());
    }

    @Test
    void emailAndPasswordLoginStillWorks() throws Exception {
        HttpResponse<String> signup = post("/api/auth/signup", csrfToken(),
                "{\"email\":\"no-google@example.com\",\"password\":\"a-long-unique-password\",\"name\":\"No Google\"}");

        assertEquals(201, signup.statusCode(), signup.body());
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
