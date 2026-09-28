package app.config;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
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

// Signs up over real HTTP, then checks the login session was written to the spring_session
// table and that the session cookie alone is enough to reach an authenticated endpoint.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginSessionPersistenceTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    @Test
    void loginSessionIsStoredInTheDatabase() throws Exception {
        String token = csrfToken();

        HttpResponse<String> signup = post("/api/auth/signup", token,
                "{\"email\":\"session-test@example.com\",\"password\":\"a-long-unique-password\",\"name\":\"Session test\"}");
        assertEquals(201, signup.statusCode(), signup.body());

        Long userId = jdbc.queryForObject("SELECT id FROM app_users WHERE email = 'session-test@example.com'", Long.class);
        // Sessions are indexed by user ID (AuthenticatedUser.getName()), not by the record's toString().
        Integer sessions = jdbc.queryForObject(
                "SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class, String.valueOf(userId));
        assertEquals(1, sessions);

        HttpResponse<String> profile = http.send(
                HttpRequest.newBuilder(uri("/api/profile")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, profile.statusCode());
        assertTrue(profile.body().contains("session-test@example.com"));
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
