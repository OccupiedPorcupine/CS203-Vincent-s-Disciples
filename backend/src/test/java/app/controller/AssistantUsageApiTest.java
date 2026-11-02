package app.controller;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
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

// Assistant usage over real HTTP (issue #65): only ROLE_ADMIN accounts see the admin views, a
// plan change shows in the account's own assistant status, and errors from the audio endpoint
// (such as SPEECH_LIMIT) reach the browser as JSON with their code.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssistantUsageApiTest {

    private static final String PASSWORD = "a-long-unique-password";

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    // Other tests delete every user, which the rows these tests create would block.
    @AfterEach
    void deleteTestRows() {
        jdbc.update("DELETE FROM assistant_daily_usage WHERE owner_id IN (SELECT id FROM app_users WHERE email LIKE '%@admin.test')");
    }

    @Test
    void onlyAdminsCanSeeUsageOrChangePlans() throws Exception {
        Session owner = signUp("owner@admin.test");
        Session admin = signUp("admin@admin.test");

        assertEquals(403, admin.get("/api/admin/assistant-usage").statusCode());
        assertEquals(403, owner.send("PUT", "/api/admin/users/1/plan", "{\"plan\":\"PRO\"}").statusCode());

        jdbc.update("UPDATE app_users SET role = 'ROLE_ADMIN' WHERE email = 'admin@admin.test'");
        admin = logIn("admin@admin.test");

        HttpResponse<String> report = admin.get("/api/admin/assistant-usage?days=7");
        assertEquals(200, report.statusCode(), report.body());
        assertTrue(report.body().contains("\"accounts\":["), report.body());
        assertEquals(400, admin.get("/api/admin/assistant-usage?days=0").statusCode());
    }

    @Test
    void aPlanChangeShowsInTheAccountsAssistantStatus() throws Exception {
        Session owner = signUp("upgrade@admin.test");
        assertTrue(owner.get("/api/assistant/status").body().contains("\"plan\":\"FREE\""));

        signUp("plans-admin@admin.test");
        jdbc.update("UPDATE app_users SET role = 'ROLE_ADMIN' WHERE email = 'plans-admin@admin.test'");
        Session admin = logIn("plans-admin@admin.test");
        Long ownerId = jdbc.queryForObject("SELECT id FROM app_users WHERE email = 'upgrade@admin.test'", Long.class);

        HttpResponse<String> changed = admin.send("PUT", "/api/admin/users/" + ownerId + "/plan", "{\"plan\":\"pro\"}");
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(400, admin.send("PUT", "/api/admin/users/" + ownerId + "/plan", "{\"plan\":\"GOLD\"}").statusCode());

        String status = owner.get("/api/assistant/status").body();
        assertTrue(status.contains("\"plan\":\"PRO\"") && status.contains("\"messagesPerDay\":500"), status);
    }

    @Test
    void speechErrorsComeBackAsJsonWithTheirCode() throws Exception {
        Session owner = signUp("speech@admin.test");

        // The test server uses the offline model, which can't speak.
        HttpResponse<String> response = owner.send("POST", "/api/assistant/speech", "{\"text\":\"Hello\"}");

        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        assertTrue(response.body().contains("\"code\":\"VOICE_UNAVAILABLE\""), response.body());
        assertTrue(owner.get("/api/assistant/status").body().contains("\"spokenRepliesUsed\":0"),
                "a failed reply is given back");
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
