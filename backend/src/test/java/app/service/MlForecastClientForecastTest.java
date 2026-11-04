package app.service;

import app.dto.MlForecastRequest;
import app.dto.MlForecastResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

// MlForecastClient.forecast (issue #106) against a stand-in ML service.
class MlForecastClientForecastTest {

    private static final MlForecastRequest REQUEST = new MlForecastRequest("2026-11-07",
            List.of(new MlForecastRequest.SalesRecord("2026-11-04", 40)), List.of("2026-08-09"));

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/forecast", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void sendsTheSalesAndHolidaysAndReadsTheQuantiles() throws Exception {
        MlForecastClient client = new MlForecastClient(serve(200, """
                {"forecast_date": "2026-11-07", "model": "chronos-2", "quantile_levels": [0.1, 0.5, 0.9],
                 "quantiles": [30.5, 40, 52.25], "median": 40}"""));

        MlForecastResponse response = client.forecast(REQUEST);

        assertEquals(List.of(0.1, 0.5, 0.9), response.quantile_levels());
        assertEquals(List.of(30.5, 40.0, 52.25), response.quantiles());
        assertTrue(received.get().contains("\"sales_history\":[{\"date\":\"2026-11-04\",\"demand\":40.0}]"), received.get());
        assertTrue(received.get().contains("\"holidays\":[\"2026-08-09\"]"), received.get());
    }

    @Test
    void anIncompleteAnswerIsAFailure() throws Exception {
        MlForecastClient client = new MlForecastClient(serve(200, """
                {"forecast_date": "2026-11-07", "model": "chronos-2", "quantile_levels": [0.1, 0.5, 0.9],
                 "quantiles": [40], "median": 40}"""));

        assertThrows(IllegalStateException.class, () -> client.forecast(REQUEST));
    }

    @Test
    void afterAFailureItSkipsTheServiceForAMinute() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T01:00:00Z"));
        MlForecastClient client = new MlForecastClient(serve(503, """
                {"code": "MODEL_LOADING", "message": "Chronos-2 is loading"}"""), "", clock);

        assertThrows(RuntimeException.class, () -> client.forecast(REQUEST));
        assertThrows(RuntimeException.class, () -> client.forecast(REQUEST));
        assertEquals(1, calls.get());

        clock.now = clock.now.plus(MlForecastClient.SKIP_AFTER_FAILURE).plusSeconds(1);
        assertThrows(RuntimeException.class, () -> client.forecast(REQUEST));
        assertEquals(2, calls.get());
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
