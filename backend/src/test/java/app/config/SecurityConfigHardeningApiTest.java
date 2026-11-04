package app.config;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

// Spring Security hardening (week 9): the H2 database console can't be reached when it's turned off,
// and responses carry headers that stop clickjacking and stop URLs leaking to other sites.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigHardeningApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theH2ConsoleIsRefusedWhenItIsTurnedOff() throws Exception {
        // The test settings leave spring.h2.console.enabled off, like the supabase and prod profiles.
        HttpResponse<String> response = get("/h2-console/");

        assertNotEquals(200, response.statusCode(), response.body());
    }

    @Test
    void responsesCannotBeFramedByOtherSites() throws Exception {
        HttpResponse<String> response = get("/api/config");

        assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""));
    }

    @Test
    void responsesDontLeakFullUrlsToOtherSites() throws Exception {
        HttpResponse<String> response = get("/api/config");

        assertEquals("strict-origin-when-cross-origin", response.headers().firstValue("Referrer-Policy").orElse(""));
    }

    @Test
    void browsersDontGuessContentTypes() throws Exception {
        HttpResponse<String> response = get("/api/config");

        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
