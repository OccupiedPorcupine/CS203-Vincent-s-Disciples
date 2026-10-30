package app.account;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// Password reset, email verification and the {code, message} error body over real HTTP and a
// real (H2) database (issue #49). Emails are captured instead of sent.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountSecurityApiTest {

    private static final Pattern TOKEN = Pattern.compile("index.html#(?:reset|verify)=([A-Za-z0-9_-]+)");
    private static final String PASSWORD = "a-long-unique-password";

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @MockitoBean
    EmailSender emailSender;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void deleteTestRows() {
        jdbc.update("DELETE FROM account_tokens WHERE user_id IN (SELECT id FROM app_users WHERE email LIKE '%@account.test')");
    }

    @Test
    void aForgottenPasswordCanBeResetOnceFromTheEmailedLink() throws Exception {
        Session signedIn = signUp("reset@account.test");
        clearInvocations(emailSender);

        assertEquals(202, new Session().send("POST", "/api/auth/password-reset", "{\"email\":\"Reset@Account.test\"}").statusCode());
        String token = emailedToken("reset@account.test", "Reset your Chicky password");

        Session anonymous = new Session();
        assertEquals(204, anonymous.send("POST", "/api/auth/password-reset/confirm",
                "{\"token\":\"" + token + "\",\"password\":\"a-brand-new-password\"}").statusCode());

        assertEquals(401, login("reset@account.test", PASSWORD).statusCode());
        assertEquals(200, login("reset@account.test", "a-brand-new-password").statusCode());
        // Signed out everywhere: the session from before the reset no longer works.
        assertEquals(401, signedIn.get("/api/profile").statusCode());

        HttpResponse<String> again = anonymous.send("POST", "/api/auth/password-reset/confirm",
                "{\"token\":\"" + token + "\",\"password\":\"yet-another-password\"}");
        assertEquals(400, again.statusCode());
        assertTrue(again.body().contains("\"code\":\"INVALID_LINK\""), again.body());
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNoEmail() throws Exception {
        clearInvocations(emailSender);

        assertEquals(202, new Session().send("POST", "/api/auth/password-reset", "{\"email\":\"nobody@account.test\"}").statusCode());
        verify(emailSender, never()).send(eq("nobody@account.test"), any(), any());
    }

    @Test
    void anExpiredResetLinkDoesNotWork() throws Exception {
        signUp("expired@account.test");
        new Session().send("POST", "/api/auth/password-reset", "{\"email\":\"expired@account.test\"}");
        String token = emailedToken("expired@account.test", "Reset your Chicky password");
        jdbc.update("UPDATE account_tokens SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE purpose = 'PASSWORD_RESET'");

        assertEquals(400, new Session().send("POST", "/api/auth/password-reset/confirm",
                "{\"token\":\"" + token + "\",\"password\":\"a-brand-new-password\"}").statusCode());
    }

    @Test
    void onlyTheLatestResetLinkWorks() throws Exception {
        signUp("latest@account.test");
        new Session().send("POST", "/api/auth/password-reset", "{\"email\":\"latest@account.test\"}");
        String first = emailedToken("latest@account.test", "Reset your Chicky password");
        new Session().send("POST", "/api/auth/password-reset", "{\"email\":\"latest@account.test\"}");
        String second = emailedToken("latest@account.test", "Reset your Chicky password");

        assertEquals(400, new Session().send("POST", "/api/auth/password-reset/confirm",
                "{\"token\":\"" + first + "\",\"password\":\"a-brand-new-password\"}").statusCode());
        assertEquals(204, new Session().send("POST", "/api/auth/password-reset/confirm",
                "{\"token\":\"" + second + "\",\"password\":\"a-brand-new-password\"}").statusCode());
    }

    @Test
    void signupEmailsALinkThatConfirmsTheAddress() throws Exception {
        clearInvocations(emailSender);
        Session session = signUp("verify@account.test");
        String token = emailedToken("verify@account.test", "Confirm your email for Chicky");

        assertTrue(session.get("/api/profile").body().contains("\"emailVerified\":false"));
        assertEquals(204, new Session().send("POST", "/api/auth/verify-email", "{\"token\":\"" + token + "\"}").statusCode());
        assertTrue(session.get("/api/profile").body().contains("\"emailVerified\":true"));
        assertEquals(400, new Session().send("POST", "/api/auth/verify-email", "{\"token\":\"" + token + "\"}").statusCode());

        clearInvocations(emailSender);
        assertEquals(202, session.send("POST", "/api/auth/verify-email/resend", null).statusCode());
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    void errorsOutsideTheControllersUseTheSameBody() throws Exception {
        HttpResponse<String> unauthenticated = new Session().get("/api/profile");
        assertEquals(401, unauthenticated.statusCode());
        assertEquals("{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"Please log in again.\"}", unauthenticated.body());

        Session session = signUp("errors@account.test");
        HttpResponse<String> noCsrf = session.http.send(HttpRequest.newBuilder(session.uri("/api/dishes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Laksa\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, noCsrf.statusCode());
        assertTrue(noCsrf.body().startsWith("{\"code\":\"FORBIDDEN\""), noCsrf.body());

        HttpResponse<String> unknown = session.get("/api/no-such-thing");
        assertEquals(404, unknown.statusCode());
        assertEquals("{\"code\":\"NOT_FOUND\",\"message\":\"Not found.\"}", unknown.body());

        HttpResponse<String> badJson = session.send("POST", "/api/dishes", "{not json");
        assertEquals(400, badJson.statusCode());
        assertEquals("{\"code\":\"INVALID_REQUEST\",\"message\":\"The request body is not valid JSON\"}", badJson.body());

        HttpResponse<String> badDate = session.get("/api/revenue?from=yesterday&to=2026-11-01");
        assertEquals(400, badDate.statusCode());
        assertEquals("{\"code\":\"INVALID_REQUEST\",\"message\":\"Missing or invalid from\"}", badDate.body());
    }

    // The token from the latest email of this subject to this address.
    private String emailedToken(String to, String subject) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, org.mockito.Mockito.atLeastOnce()).send(eq(to), eq(subject), text.capture());
        Matcher matcher = TOKEN.matcher(text.getValue());
        assertTrue(matcher.find(), text.getValue());
        assertTrue(text.getValue().contains("http://localhost:5500/"), text.getValue());
        return matcher.group(1);
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return new Session().send("POST", "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private Session signUp(String email) throws Exception {
        Session session = new Session();
        HttpResponse<String> signup = session.send("POST", "/api/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Stall owner\"}");
        assertEquals(201, signup.statusCode(), signup.body());
        return session;
    }

    private final class Session {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            String csrf = get("/api/csrf").body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf);
            assertTrue(matcher.find(), csrf);
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", matcher.group(1))
                            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
