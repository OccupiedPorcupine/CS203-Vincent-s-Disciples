package app.service;

import app.exception.InvalidForecastDateException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// Which NEA forecast each day gets (issue #51), and the cache, against a stand-in data.gov.sg.
class NeaWeatherForecastServiceTest {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    // Wednesday 4 November 2026, 9 am in Singapore.
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static final String TWENTY_FOUR_HOUR = """
            {"code": 0, "data": {"records": [{"general": {
              "forecast": {"code": "PC", "text": "Partly Cloudy"},
              "wind": {"direction": "NNE", "speed": {"low": 10, "high": 20}},
              "temperature": {"low": 25, "high": 32, "unit": "Degrees Celsius"},
              "validPeriod": {"start": "2026-11-04T06:00:00+08:00", "end": "2026-11-05T06:00:00+08:00"}}}]}}
            """;
    private static final String OUTLOOK = """
            {"code": 0, "data": {"records": [{"date": "2026-11-04", "forecasts": [
              %s, %s, %s, %s]}]}}
            """.formatted(day("2026-11-05", "TL", "Thundery Showers"), day("2026-11-06", "TL", "Thundery Showers"),
            day("2026-11-07", "FA", "Fair"), day("2026-11-08", "SH", "Showers"));

    private static String day(String date, String code, String text) {
        return """
                {"timestamp": "%sT00:00:00+08:00", "forecast": {"code": "%s", "text": "%s", "summary": "%s"},
                 "temperature": {"low": 24, "high": 33, "unit": "Degrees Celsius"},
                 "wind": {"direction": "VARIABLE", "speed": {"low": 5, "high": 15}}}
                """.formatted(date, code, text, text);
    }

    static class MutableClock extends Clock {
        Instant now = TODAY.atTime(9, 0).atZone(SINGAPORE).toInstant();

        @Override public ZoneId getZone() { return SINGAPORE; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private HttpServer server;
    private final AtomicInteger outlookRequests = new AtomicInteger();
    private volatile boolean down;
    private final MutableClock clock = new MutableClock();
    private NeaWeatherForecastService service;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/real-time/api/twenty-four-hr-forecast", exchange -> reply(exchange, TWENTY_FOUR_HOUR));
        server.createContext("/v2/real-time/api/four-day-outlook", exchange -> {
            outlookRequests.incrementAndGet();
            reply(exchange, OUTLOOK);
        });
        server.start();
        service = new NeaWeatherForecastService(new WeatherFeatureAdapter(), clock,
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        if (down) {
            exchange.sendResponseHeaders(503, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void todayUsesThe24HourForecastAndTheNext4DaysTheOutlook() {
        var today = service.forecast(TODAY);
        assertEquals(NeaWeatherForecastService.Source.NEA_24_HOUR, today.source());
        assertEquals("Partly Cloudy, 25 to 32°C", today.description());

        var tomorrow = service.forecast(TODAY.plusDays(1));
        assertEquals(NeaWeatherForecastService.Source.NEA_4_DAY_OUTLOOK, tomorrow.source());
        assertEquals("Thundery Showers, 24 to 33°C", tomorrow.description());
        assertTrue(tomorrow.input().precipitation() > 0);

        var inFourDays = service.forecast(TODAY.plusDays(4));
        assertEquals("Showers, 24 to 33°C", inFourDays.description());
        assertEquals(0.0, service.forecast(TODAY.plusDays(3)).input().precipitation());

        assertThrows(InvalidForecastDateException.class, () -> service.forecast(TODAY.plusDays(5)));
    }

    @Test
    void theOutlookIsFetchedOnceAndAnOldOneIsUsedWhileNeaIsDown() {
        service.forecast(TODAY.plusDays(1));
        service.forecast(TODAY.plusDays(2));
        assertEquals(1, outlookRequests.get());

        // After the cache time, NEA is asked again; while it's down, the last outlook is used.
        clock.now = clock.now.plus(NeaWeatherForecastService.CACHE_FOR).plusSeconds(1);
        down = true;
        assertEquals("Fair, 24 to 33°C", service.forecast(TODAY.plusDays(3)).description());
        assertEquals(2, outlookRequests.get());

        // Too old to trust: no weather, so the forecast falls back to the baseline.
        clock.now = clock.now.plus(NeaWeatherForecastService.STALE_FOR);
        assertThrows(RuntimeException.class, () -> service.forecast(TODAY.plusDays(3)));
    }
}
