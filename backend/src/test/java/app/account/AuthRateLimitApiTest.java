package app.account;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
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

// Login and reset limits over real HTTP (issue #49). The other tests turn the limits off.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.auth.rate-limits.enabled=true")
class AuthRateLimitApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @MockitoBean
    EmailSender emailSender;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @Test
    void guessingOnePasswordIsStoppedAfterTenTries() throws Exception {
        for (int attempt = 1; attempt <= AuthRateLimiter.Limit.LOGIN_PER_EMAIL.max; attempt++) {
            assertEquals(401, post("/api/auth/login", "{\"email\":\"target@limits.test\",\"password\":\"guess-" + attempt + "\"}").statusCode());
        }

        HttpResponse<String> blocked = post("/api/auth/login", "{\"email\":\"TARGET@limits.test\",\"password\":\"another-guess\"}");

        assertEquals(429, blocked.statusCode());
        assertEquals("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many attempts. Try again in 15 minutes.\"}", blocked.body());
        assertTrue(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow()) > 800);
        // Another account isn't affected.
        assertEquals(401, post("/api/auth/login", "{\"email\":\"someone-else@limits.test\",\"password\":\"guess\"}").statusCode());
    }

    @Test
    void resetEmailsToOneAddressAreLimited() throws Exception {
        for (int attempt = 1; attempt <= AuthRateLimiter.Limit.RESET_PER_EMAIL.max; attempt++) {
            assertEquals(202, post("/api/auth/password-reset", "{\"email\":\"flood@limits.test\"}").statusCode());
        }

        assertEquals(429, post("/api/auth/password-reset", "{\"email\":\"flood@limits.test\"}").statusCode());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        String csrf = http.send(HttpRequest.newBuilder(uri("/api/csrf")).build(), HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf);
        assertTrue(matcher.find(), csrf);
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", matcher.group(1))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
