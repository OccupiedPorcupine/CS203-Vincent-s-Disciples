package app.service;

import app.dto.MlPredictionRequest;
import app.exception.InvalidForecastDateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

// NEA's weather forecasts from data.gov.sg, as the forecast model's weather inputs (issue #51).
// Today comes from the 24-hour forecast; the next 4 days from the 4-day outlook. Later dates have
// no weather forecast, so they throw InvalidForecastDateException and the baseline is used.
//
// Each response is kept for CACHE_FOR, so a page of forecasts or a prep list calls NEA once. If NEA
// is slow or down, the last response is used for up to STALE_FOR before giving up.
@Service
public class NeaWeatherForecastService {

    private static final Logger log = LoggerFactory.getLogger(NeaWeatherForecastService.class);
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    static final Duration CACHE_FOR = Duration.ofMinutes(30);
    static final Duration STALE_FOR = Duration.ofHours(6);

    public enum Source {
        NEA_24_HOUR("NEA's 24-hour forecast"),
        NEA_4_DAY_OUTLOOK("NEA's 4-day outlook");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // The model's inputs, where they came from, and the forecast in words, e.g. "Thundery Showers, 24 to 33°C".
    public record Forecast(MlPredictionRequest.WeatherInput input, Source source, String description) {}

    private final RestClient restClient;
    private final WeatherFeatureAdapter weatherFeatureAdapter;
    private final Clock clock;
    private final Cached<TwentyFourHour> twentyFourHour = new Cached<>();
    private final Cached<Outlook> outlook = new Cached<>();

    @Autowired
    public NeaWeatherForecastService(WeatherFeatureAdapter weatherFeatureAdapter, Clock clock,
                                     @Value("${app.nea.base-url:https://api-open.data.gov.sg}") String baseUrl) {
        this.weatherFeatureAdapter = weatherFeatureAdapter;
        this.clock = clock;
        // Short timeouts: a slow NEA falls back to the cached forecast, or to the baseline.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public Forecast forecast(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(SINGAPORE));
        if (date.equals(today)) {
            General general = twentyFourHour.get(this::fetchTwentyFourHour, "24-hour forecast").general();
            if (general == null) throw new IllegalStateException("NEA's 24-hour forecast has no general forecast");
            return forecast(general.forecast(), general.temperature(), general.wind(), Source.NEA_24_HOUR);
        }

        Outlook days = outlook.get(this::fetchOutlook, "4-day outlook");
        for (OutlookDay day : days.forecasts()) {
            if (OffsetDateTime.parse(day.timestamp()).atZoneSameInstant(SINGAPORE).toLocalDate().equals(date)) {
                return forecast(day.forecast(), day.temperature(), day.wind(), Source.NEA_4_DAY_OUTLOOK);
            }
        }
        throw new InvalidForecastDateException("NEA's weather outlook doesn't cover " + date + " yet");
    }

    public MlPredictionRequest.WeatherInput getWeather(LocalDate date) {
        return forecast(date).input();
    }

    private Forecast forecast(Condition condition, Temperature temperature, Wind wind, Source source) {
        MlPredictionRequest.WeatherInput input = weatherFeatureAdapter.adapt(temperature.low(), temperature.high(),
                wind.speed().low(), wind.speed().high(), condition.code());
        String description = "%s, %.0f to %.0f°C".formatted(condition.text(), temperature.low(), temperature.high());
        return new Forecast(input, source, description);
    }

    private TwentyFourHour fetchTwentyFourHour() {
        ApiResponse<TwentyFourHour> response = restClient.get().uri("/v2/real-time/api/twenty-four-hr-forecast")
                .retrieve().body(new ParameterizedTypeReference<>() {});
        return first(response, "24-hour forecast");
    }

    private Outlook fetchOutlook() {
        ApiResponse<Outlook> response = restClient.get().uri("/v2/real-time/api/four-day-outlook")
                .retrieve().body(new ParameterizedTypeReference<>() {});
        Outlook outlook = first(response, "4-day outlook");
        if (outlook.forecasts() == null) throw new IllegalStateException("NEA's 4-day outlook has no forecasts");
        return outlook;
    }

    private static <T> T first(ApiResponse<T> response, String name) {
        if (response == null || response.data() == null || response.data().records() == null || response.data().records().isEmpty()) {
            throw new IllegalStateException("NEA " + name + " unavailable");
        }
        return response.data().records().getFirst();
    }

    // The last good response from one NEA endpoint.
    private final class Cached<T> {
        private T value;
        private Instant fetchedAt;

        synchronized T get(Supplier<T> fetch, String name) {
            Instant now = clock.instant();
            if (value != null && fetchedAt.plus(CACHE_FOR).isAfter(now)) return value;
            try {
                value = fetch.get();
                fetchedAt = now;
                return value;
            } catch (RuntimeException exception) {
                if (value != null && fetchedAt.plus(STALE_FOR).isAfter(now)) {
                    log.warn("NEA {} unavailable, using the one from {}: {}", name, fetchedAt, exception.toString());
                    return value;
                }
                throw exception;
            }
        }
    }

    private record ApiResponse<T>(Data<T> data) {}
    private record Data<T>(List<T> records) {}
    private record TwentyFourHour(General general) {}
    private record General(Condition forecast, Wind wind, Temperature temperature) {}
    private record Outlook(List<OutlookDay> forecasts) {}
    private record OutlookDay(String timestamp, Condition forecast, Temperature temperature, Wind wind) {}
    private record Condition(String text, String code) {}
    private record Wind(String direction, Speed speed) {}
    private record Speed(double high, double low) {}
    private record Temperature(double high, double low) {}
}
