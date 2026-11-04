package app.account;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// POST /api/auth/logout (SecurityConfig): the stored session is deleted, the browser is told to drop
// the login cookie, and a copy of the old cookie no longer signs anyone in.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogoutCleanupApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

    @Test
    void logoutAnswersNoContent() throws Exception {
        signUp();

        assertEquals(204, logOut(csrfToken()).statusCode());
    }

    @Test
    void logoutDeletesTheStoredSession() throws Exception {
        long userId = signUp();
        assertEquals(1, storedSessions(userId));

        logOut(csrfToken());

        assertEquals(0, storedSessions(userId));
    }

    @Test
    void logoutTellsTheBrowserToDropTheLoginCookie() throws Exception {
        signUp();

        HttpResponse<String> logout = logOut(csrfToken());

        List<String> setCookies = logout.headers().allValues("Set-Cookie");
        assertTrue(setCookies.stream().anyMatch(header -> header.startsWith("SESSION=")
                        && header.replace(" ", "").toLowerCase().contains("max-age=0")),
                "SESSION cookie not expired: " + setCookies);
        // The app's login cookie is SESSION (Spring Session), not the servlet default JSESSIONID.
        assertFalse(setCookies.stream().anyMatch(header -> header.startsWith("JSESSIONID=")),
                "Logout clears a cookie the app never sets: " + setCookies);
    }

    @Test
    void theBrowserIsSignedOutAfterLogout() throws Exception {
        signUp();
        assertEquals(200, profile(http).statusCode());

        logOut(csrfToken());

        assertEquals(401, profile(http).statusCode());
    }

    @Test
    void aCopyOfTheOldLoginCookieNoLongerWorks() throws Exception {
        signUp();
        String oldCookie = sessionCookie();

        logOut(csrfToken());

        // A fresh client that only replays the old cookie, as someone who copied it would.
        HttpClient replay = HttpClient.newHttpClient();
        HttpResponse<String> response = replay.send(HttpRequest.newBuilder(uri("/api/profile"))
                        .header("Cookie", "SESSION=" + oldCookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
    }

    @Test
    void logoutWithoutTheCsrfTokenIsRefusedAndKeepsTheUserSignedIn() throws Exception {
        // Otherwise another site could sign people out with a hidden form.
        long userId = signUp();

        HttpResponse<String> logout = http.send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, logout.statusCode());
        assertEquals(1, storedSessions(userId));
        assertEquals(200, profile(http).statusCode());
    }

    private long signUp() throws Exception {
        String email = "logout-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> signup = http.send(HttpRequest.newBuilder(uri("/api/auth/signup"))
                        .header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", csrfToken())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + email + "\",\"password\":\"a-long-unique-password\",\"name\":\"Logout test\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, signup.statusCode(), signup.body());
        return jdbc.queryForObject("SELECT id FROM app_users WHERE email = ?", Long.class, email);
    }

    private HttpResponse<String> logOut(String csrfToken) throws Exception {
        return http.send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                        .header("X-XSRF-TOKEN", csrfToken)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> profile(HttpClient client) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/api/profile")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int storedSessions(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                Integer.class, String.valueOf(userId));
    }

    private String sessionCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("SESSION"))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Not signed in: no SESSION cookie"));
    }

    private String csrfToken() throws Exception {
        String body = http.send(HttpRequest.newBuilder(uri("/api/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
