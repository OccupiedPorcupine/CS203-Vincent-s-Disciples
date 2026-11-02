package app.config;

import app.service.MlForecastClient;
import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

// GET /api/health for uptime checks (issue #48), over real HTTP and signed out.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthApiTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @MockitoBean
    MlForecastClient mlClient;

    @LocalServerPort
    int port;

    @Test
    void isUpWhenTheDatabaseAndTheMlServiceAnswer() throws Exception {
        when(mlClient.isUp()).thenReturn(true);

        HttpResponse<String> response = get("/api/health");
        JsonNode health = new ObjectMapper().readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("UP", health.get("status").asString());
        assertEquals(List.of("db", "ml", "ping"), StreamSupport.stream(health.get("components").propertyNames().spliterator(), false).toList());
        assertEquals("UP", health.at("/components/db/status").asString());
        assertEquals("UP", health.at("/components/ml/status").asString());
        // Statuses only: nothing about the database or the ML address.
        assertFalse(response.body().contains("details"), response.body());
    }

    // Forecasts fall back to the baseline without the ML service, so the app is degraded, not down.
    @Test
    void isDegradedButStill200WhenTheMlServiceIsDown() throws Exception {
        when(mlClient.isUp()).thenReturn(false);

        HttpResponse<String> response = get("/api/health");
        JsonNode health = new ObjectMapper().readTree(response.body());

        assertEquals(200, response.statusCode());
        assertEquals("DEGRADED", health.get("status").asString());
        assertEquals("DEGRADED", health.at("/components/ml/status").asString());
        assertEquals("UP", health.at("/components/db/status").asString());
    }

    @Test
    void onlyHealthIsExposed() throws Exception {
        assertNotEquals(200, get("/api").statusCode());
        assertNotEquals(200, get("/api/env").statusCode());
        assertNotEquals(200, get("/actuator/health").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
