package app.account;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionExpiryApiTest {
    @MockitoBean PublicHolidaySyncService publicHolidaySyncService;
    @MockitoBean EmailSender emailSender;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FindByIndexNameSessionRepository<?> sessions;

    @Test
    void defaultsAndPreferencesPersistButDoNotExtendExistingSessions() throws Exception {
        Client client = signup();
        String settings = client.get("/api/account/session-settings").body();
        assertTrue(settings.contains("\"idleMinutes\":60"), settings);
        assertTrue(settings.contains("\"absoluteMinutes\":480"), settings);
        Session original = session(client);
        assertEquals(3600, original.getMaxInactiveInterval().toSeconds());
        long deadline = original.getAttribute(SessionExpiry.ABSOLUTE_DEADLINE);
        assertTrue(Math.abs(deadline - System.currentTimeMillis() - 8 * 3600_000L) < 10_000);

        assertEquals(200, client.send("PUT", "/api/account/session-settings",
                "{\"idleMinutes\":30,\"absoluteMinutes\":120}").statusCode());
        assertEquals(deadline, (Long) session(client).getAttribute(SessionExpiry.ABSOLUTE_DEADLINE));
        assertEquals(3600, session(client).getMaxInactiveInterval().toSeconds());
        assertTrue(client.get("/api/account/session-settings").body().contains("\"idleMinutes\":30"));

        Client otherUser = signup();
        assertTrue(otherUser.get("/api/account/session-settings").body().contains("\"idleMinutes\":60"));
        Client nextLogin = new Client();
        nextLogin.email = client.email;
        assertEquals(200, nextLogin.send("POST", "/api/auth/login", credentials(client.email)).statusCode());
        // Find the newest session; the other login remains independent.
        Session newest = sessions.findByPrincipalName(userId(client)).values().stream()
                .max(java.util.Comparator.comparing(Session::getCreationTime)).orElseThrow();
        assertEquals(1800, newest.getMaxInactiveInterval().toSeconds());
        long newDeadline = newest.getAttribute(SessionExpiry.ABSOLUTE_DEADLINE);
        assertTrue(Math.abs(newDeadline - System.currentTimeMillis() - 2 * 3600_000L) < 10_000);
    }

    @Test
    void idleSessionCannotBeReused() throws Exception {
        Client client = signup();
        long past = System.currentTimeMillis() - 61 * 60_000L;
        jdbc.update("UPDATE spring_session SET last_access_time = ?, expiry_time = ? WHERE principal_name = ?",
                past, past + 3600_000L, userId(client));
        assertEquals(401, client.get("/api/profile").statusCode());
        assertEquals(401, client.get("/api/profile").statusCode());
    }

    @Test
    void absoluteDeadlineRejectsAnActiveSessionAndAllowsFreshLogin() throws Exception {
        Client client = signup();
        assertEquals(200, client.get("/api/profile").statusCode());
        expire(sessions, userId(client));
        assertEquals(401, client.get("/api/profile").statusCode());
        assertEquals(0, sessions.findByPrincipalName(userId(client)).size());
        assertEquals(200, client.send("POST", "/api/auth/login", credentials(client.email)).statusCode());
        assertEquals(200, client.get("/api/profile").statusCode());
    }

    @Test
    void settingsRequireAuthenticationCsrfAndValidBounds() throws Exception {
        assertEquals(401, new Client().get("/api/account/session-settings").statusCode());
        Client client = signup();
        assertEquals(403, client.raw("PUT", "/api/account/session-settings",
                "{\"idleMinutes\":30,\"absoluteMinutes\":120}", null).statusCode());
        for (String invalid : new String[]{"{}", "{\"idleMinutes\":0,\"absoluteMinutes\":480}",
                "{\"idleMinutes\":60,\"absoluteMinutes\":0}",
                "{\"idleMinutes\":1441,\"absoluteMinutes\":10080}",
                "{\"idleMinutes\":60,\"absoluteMinutes\":10081}",
                "{\"idleMinutes\":120,\"absoluteMinutes\":60}"}) {
            assertEquals(400, client.send("PUT", "/api/account/session-settings", invalid).statusCode(), invalid);
        }
    }

    private <S extends Session> void expire(FindByIndexNameSessionRepository<S> repository, String principal) {
        S session = repository.findByPrincipalName(principal).values().iterator().next();
        session.setAttribute(SessionExpiry.ABSOLUTE_DEADLINE, System.currentTimeMillis() - 1);
        repository.save(session);
    }

    private Session session(Client client) { return sessions.findByPrincipalName(userId(client)).values().iterator().next(); }
    private String userId(Client client) {
        return String.valueOf(jdbc.queryForObject("SELECT id FROM app_users WHERE email = ?", Long.class, client.email));
    }
    private String credentials(String email) { return "{\"email\":\"" + email + "\",\"password\":\"a-long-unique-password\"}"; }
    private Client signup() throws Exception {
        Client client = new Client();
        client.email = UUID.randomUUID() + "@expiry.test";
        assertEquals(201, client.send("POST", "/api/auth/signup",
                "{\"email\":\"" + client.email + "\",\"password\":\"a-long-unique-password\",\"name\":\"Expiry\"}").statusCode());
        return client;
    }
    private class Client {
        String email;
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> get(String path) throws Exception { return raw("GET", path, "", null); }
        HttpResponse<String> send(String method, String path, String body) throws Exception {
            var matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(get("/api/csrf").body());
            assertTrue(matcher.find());
            return raw(method, path, body, matcher.group(1));
        }
        HttpResponse<String> raw(String method, String path, String body, String csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
