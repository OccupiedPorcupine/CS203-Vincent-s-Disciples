package app.weather;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Rain for the Actions needed panel's rain card (issue #96): NEA's 4-day outlook for the days ahead,
// and Open-Meteo's archive for how much rain fell on each past day. data.gov.sg has past rainfall too,
// but only as 5-minute readings, 25 to a page: about 12 requests per day of history, too slow for the
// dashboard. Both are kept in memory, and a failure just means no rain data, never an error.
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    // Open-Meteo's daily totals are for one point. Hawker centres have no coordinates, so the middle of Singapore.
    static final double LATITUDE = 1.3521;
    static final double LONGITUDE = 103.8198;
    static final Duration OUTLOOK_FOR = Duration.ofHours(1);
    static final Duration RAINFALL_FOR = Duration.ofHours(12);
    // After a failure, so an outage doesn't slow every dashboard load with a timeout.
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    private static final Pattern RAIN = Pattern.compile("(?i)shower|rain|thunder|drizzle");

    private final RestClient restClient;
    private final String neaBaseUrl;
    private final String archiveBaseUrl;
    private final Clock clock;
    private final boolean enabled;

    private List<Forecast> outlook = List.of();
    private Instant outlookExpires = Instant.EPOCH;
    private Map<LocalDate, Double> rainfall = Map.of();
    private LocalDate rainfallFrom;
    private LocalDate rainfallTo;
    private Instant rainfallExpires = Instant.EPOCH;

    // One day of NEA's outlook, e.g. (Sat 7 Nov, "Thundery Showers").
    public record Forecast(LocalDate date, String text) {
        public boolean rain() {
            return RAIN.matcher(text).find();
        }
    }

    @Autowired
    public WeatherService(Clock clock, @Value("${app.weather.enabled:true}") boolean enabled) {
        this(RestClient.builder().requestFactory(requestFactory()), "https://api-open.data.gov.sg",
                "https://archive-api.open-meteo.com", clock, enabled);
    }

    WeatherService(RestClient.Builder builder, String neaBaseUrl, String archiveBaseUrl, Clock clock, boolean enabled) {
        this.restClient = builder.build();
        this.neaBaseUrl = neaBaseUrl;
        this.archiveBaseUrl = archiveBaseUrl;
        this.clock = clock;
        this.enabled = enabled;
    }

    // Short, because the dashboard waits for these.
    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    // NEA's forecast for the next four days, soonest first; empty if it can't be fetched.
    public synchronized List<Forecast> outlook() {
        if (!enabled) return List.of();
        Instant now = clock.instant();
        if (now.isBefore(outlookExpires)) return outlook;
        try {
            JsonNode response = restClient.get().uri(neaBaseUrl + "/v2/real-time/api/four-day-outlook")
                    .retrieve().body(JsonNode.class);
            List<Forecast> days = new ArrayList<>();
            for (JsonNode day : response.path("data").path("records").path(0).path("forecasts")) {
                days.add(new Forecast(OffsetDateTime.parse(day.path("timestamp").asString()).toLocalDate(),
                        day.path("forecast").path("text").asString()));
            }
            outlook = days;
            outlookExpires = now.plus(OUTLOOK_FOR);
        } catch (RuntimeException e) {
            log.warn("NEA's 4-day outlook could not be fetched: {}", e.getMessage());
            outlook = List.of();
            outlookExpires = now.plus(RETRY_AFTER);
        }
        return outlook;
    }

    // Each day's rainfall in mm. Days Open-Meteo has no figure for are left out, and everything is
    // if it can't be fetched.
    public synchronized Map<LocalDate, Double> rainfall(LocalDate from, LocalDate to) {
        if (!enabled || to.isBefore(from)) return Map.of();
        Instant now = clock.instant();
        boolean covered = rainfallFrom != null && !from.isBefore(rainfallFrom) && !to.isAfter(rainfallTo);
        if (!covered || !now.isBefore(rainfallExpires)) {
            rainfallFrom = from;
            rainfallTo = to;
            try {
                rainfall = fetchRainfall(from, to);
                rainfallExpires = now.plus(RAINFALL_FOR);
            } catch (RuntimeException e) {
                log.warn("Past rainfall could not be fetched: {}", e.getMessage());
                rainfall = Map.of();
                rainfallExpires = now.plus(RETRY_AFTER);
            }
        }
        return rainfall.entrySet().stream()
                .filter(day -> !day.getKey().isBefore(from) && !day.getKey().isAfter(to))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<LocalDate, Double> fetchRainfall(LocalDate from, LocalDate to) {
        JsonNode daily = restClient.get()
                .uri(archiveBaseUrl + "/v1/archive?latitude={latitude}&longitude={longitude}&start_date={from}&end_date={to}"
                        + "&daily=precipitation_sum&timezone=Asia/Singapore", LATITUDE, LONGITUDE, from, to)
                .retrieve().body(JsonNode.class)
                .path("daily");
        JsonNode dates = daily.path("time");
        JsonNode totals = daily.path("precipitation_sum");
        Map<LocalDate, Double> days = new HashMap<>();
        for (int i = 0; i < dates.size(); i++) {
            if (totals.path(i).isNumber()) {
                days.put(LocalDate.parse(dates.path(i).asString()), totals.path(i).doubleValue());
            }
        }
        return days;
    }
}
