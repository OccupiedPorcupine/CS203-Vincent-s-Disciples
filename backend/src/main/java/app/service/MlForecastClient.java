package app.service;

import app.dto.MlForecastRequest;
import app.dto.MlForecastResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class MlForecastClient {

    private final RestClient restClient;
    private final Clock clock;
    // After a failed forecast, the next ones skip the ML service until then, so a prep list of many
    // dishes doesn't wait out the timeout for each of them.
    private volatile Instant skipUntil = Instant.MIN;

    // Sent to the ML service when ML_SHARED_SECRET is set, for hosts where it can't be kept
    // private (on Vercel it already is; see deploy/README.md).
    static final String SECRET_HEADER = "X-ML-Secret";
    static final Duration SKIP_AFTER_FAILURE = Duration.ofMinutes(1);

    public MlForecastClient(String baseUrl) {
        this(baseUrl, "", Clock.systemUTC());
    }

    @Autowired
    public MlForecastClient(@Value("${app.ml.base-url}") String baseUrl, @Value("${app.ml.shared-secret:}") String sharedSecret,
                            Clock clock) {
        // After a cold start the ML service takes several seconds to load Chronos-2, and waits up to
        // 15 s for it before answering, so the read timeout is a little longer than that.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        RestClient.Builder builder = RestClient.builder()
                // Strip a trailing slash so the base URL joins cleanly with "/forecast".
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory);
        if (!sharedSecret.isBlank()) {
            builder.defaultHeader(SECRET_HEADER, sharedSecret);
        }
        this.restClient = builder.build();
        this.clock = clock;
    }

    // Chronos-2's forecast for one dish. Throws when the service is down, still loading the model
    // after a cold start, or failed within the last SKIP_AFTER_FAILURE.
    public MlForecastResponse forecast(MlForecastRequest request) {
        if (clock.instant().isBefore(skipUntil)) {
            throw new IllegalStateException("The ML service failed less than a minute ago");
        }
        try {
            MlForecastResponse response = restClient
                    .post()
                    .uri("/forecast")
                    .body(request)
                    .retrieve()
                    .body(MlForecastResponse.class);
            if (response == null || response.quantiles() == null || response.quantile_levels() == null
                    || response.quantiles().size() != response.quantile_levels().size() || response.quantiles().isEmpty()) {
                throw new IllegalStateException("The ML service sent an incomplete forecast");
            }
            return response;
        } catch (RuntimeException exception) {
            skipUntil = clock.instant().plus(SKIP_AFTER_FAILURE);
            throw exception;
        }
    }

    // Whether the ML service answers its /health check. Never throws.
    public boolean isUp() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException unreachable) {
            return false;
        }
    }
}
