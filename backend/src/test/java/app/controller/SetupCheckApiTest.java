package app.controller;

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

// The setup checks over real HTTP: admins only, and the test settings (no SMTP, no Telegram, the
// offline assistant, no ML service) come back as what they are.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetupCheckApiTest {

    private static final String PASSWORD = "a-long-unique-password";

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void onlyAdminsCanRunTheChecks() throws Exception {
        Session owner = signUp("owner@setup.test");

        assertEquals(403, owner.get("/api/admin/setup-checks").statusCode());
        assertEquals(403, owner.send("POST", "/api/admin/setup-checks/email").statusCode());
    }

    @Test
    void eachIntegrationSaysWhatItIs() throws Exception {
        signUp("admin@setup.test");
        jdbc.update("UPDATE app_users SET role = 'ROLE_ADMIN' WHERE email = 'admin@setup.test'");
        Session admin = logIn("admin@setup.test");

        HttpResponse<String> checks = admin.get("/api/admin/setup-checks");
        assertEquals(200, checks.statusCode(), checks.body());
        String body = checks.body();
        assertTrue(body.contains("{\"name\":\"assistant\",\"status\":\"OFF\""), body);
        assertTrue(body.contains("{\"name\":\"chat reminders\",\"status\":\"OFF\""), body);
        assertTrue(body.contains("{\"name\":\"email\",\"status\":\"OFF\""), body);
        assertTrue(body.contains("{\"name\":\"google sign-in\",\"status\":\"OK\""), body);
        assertTrue(body.contains("{\"name\":\"ml\",\"status\":\"PROBLEM\""), body);
        assertTrue(body.contains("{\"name\":\"telegram\",\"status\":\"OFF\""), body);

        HttpResponse<String> testEmail = admin.send("POST", "/api/admin/setup-checks/email");
        assertEquals(200, testEmail.statusCode(), testEmail.body());
        assertTrue(testEmail.body().contains("\"status\":\"OFF\""), testEmail.body());
    }

    private Session signUp(String email) throws Exception {
        Session session = new Session();
        HttpResponse<String> signup = session.send("POST", "/api/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Stall owner\"}");
        assertEquals(201, signup.statusCode(), signup.body());
        return session;
    }

    // The role is read when signing in, so a new admin signs in again.
    private Session logIn(String email) throws Exception {
        Session session = new Session();
        HttpResponse<String> login = session.send("POST", "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, login.statusCode(), login.body());
        return session;
    }

    private final class Session {
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path) throws Exception {
            return send(method, path, null);
        }

        HttpResponse<String> send(String method, String path, String json) throws Exception {
            String body = get("/api/csrf").body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            assertTrue(matcher.find(), body);
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", matcher.group(1))
                            .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
