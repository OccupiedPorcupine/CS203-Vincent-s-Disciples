package app.weather;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// Reading NEA's 4-day outlook and Open-Meteo's past rainfall, against a fake server (issue #96).
class WeatherServiceTest {

    static final String OUTLOOK = "https://nea.test/v2/real-time/api/four-day-outlook";
    static final String ARCHIVE = "https://archive.test/v1/archive?latitude=1.3521&longitude=103.8198"
            + "&start_date=2026-10-01&end_date=2026-10-03&daily=precipitation_sum&timezone=Asia/Singapore";

    // The shape NEA returned on 5 Nov 2026, cut to two days.
    static final String OUTLOOK_JSON = """
            {"code": 0, "data": {"records": [{"date": "2026-11-05", "forecasts": [
              {"day": "Friday", "timestamp": "2026-11-06T00:00:00+08:00",
               "forecast": {"code": "TL", "summary": "Afternoon thundery showers", "text": "Thundery Showers"}},
              {"day": "Saturday", "timestamp": "2026-11-07T00:00:00+08:00",
               "forecast": {"code": "FA", "summary": "Fair", "text": "Fair"}}]}]}}
            """;
    static final String ARCHIVE_JSON = """
            {"daily": {"time": ["2026-10-01", "2026-10-02", "2026-10-03"], "precipitation_sum": [7.4, null, 0.0]}}
            """;

    MutableClock clock;
    MockRestServiceServer server;
    WeatherService weather;

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T02:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.ofHours(8); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        weather = new WeatherService(builder, "https://nea.test", "https://archive.test", clock, true);
    }

    @Test
    void theOutlookIsReadByDateAndKeptForAnHour() {
        server.expect(requestTo(OUTLOOK)).andRespond(withSuccess(OUTLOOK_JSON, MediaType.APPLICATION_JSON));

        List<WeatherService.Forecast> days = weather.outlook();
        assertEquals(List.of(new WeatherService.Forecast(LocalDate.of(2026, 11, 6), "Thundery Showers"),
                new WeatherService.Forecast(LocalDate.of(2026, 11, 7), "Fair")), days);
        assertTrue(days.get(0).rain());
        assertFalse(days.get(1).rain());

        clock.now = clock.now.plus(WeatherService.OUTLOOK_FOR).minusSeconds(1);
        assertEquals(days, weather.outlook()); // no second request
        server.verify();
    }

    @Test
    void pastRainfallLeavesOutDaysWithNoFigureAndIsKeptForTheSameRange() {
        server.expect(requestTo(ARCHIVE)).andRespond(withSuccess(ARCHIVE_JSON, MediaType.APPLICATION_JSON));

        Map<LocalDate, Double> rainfall = weather.rainfall(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3));
        assertEquals(Map.of(LocalDate.of(2026, 10, 1), 7.4, LocalDate.of(2026, 10, 3), 0.0), rainfall);
        assertEquals(Map.of(LocalDate.of(2026, 10, 3), 0.0),
                weather.rainfall(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3))); // within the range: no request
        server.verify();
    }

    @Test
    void aFailureMeansNoWeatherAndIsNotRetriedOnEveryPageLoad() {
        server.expect(requestTo(OUTLOOK)).andRespond(withServerError());

        assertEquals(List.of(), weather.outlook());
        assertEquals(List.of(), weather.outlook());
        server.verify();

        server.reset();
        server.expect(requestTo(OUTLOOK)).andRespond(withSuccess(OUTLOOK_JSON, MediaType.APPLICATION_JSON));
        clock.now = clock.now.plus(WeatherService.RETRY_AFTER);
        assertEquals(2, weather.outlook().size());
        server.verify();
    }

    @Test
    void whenTurnedOffNothingIsFetched() {
        WeatherService off = new WeatherService(RestClient.builder(), "https://nea.test", "https://archive.test", clock, false);
        assertEquals(List.of(), off.outlook());
        assertEquals(Map.of(), off.rainfall(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)));
    }
}
