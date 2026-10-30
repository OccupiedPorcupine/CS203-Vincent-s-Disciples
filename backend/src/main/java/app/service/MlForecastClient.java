package app.service;

import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MlForecastClient {

    private final RestClient restClient;

    public MlForecastClient() {
        // Short timeouts, so a stuck ML service falls back to the baseline forecast quickly.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:8000")
                .requestFactory(requestFactory)
                .build();
    }

    public MlPredictionResponse predict(
            MlPredictionRequest request
    ) {
        return restClient
                .post()
                .uri("/predict")
                .body(request)
                .retrieve()
                .body(MlPredictionResponse.class);
    }

    // The dishes the model was trained on, normalised. Empty when the ML service doesn't say
    // (an older service without /model-info, or artifacts without metadata).
    public Optional<Set<String>> knownDishes() {
        ModelInfo info;
        try {
            info = restClient.get().uri("/model-info").retrieve().body(ModelInfo.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
        if (info == null || info.dishes() == null) {
            return Optional.empty();
        }
        return Optional.of(info.dishes().stream().map(MlForecastClient::normalise).collect(Collectors.toSet()));
    }

    static String normalise(String dishName) {
        return dishName.strip().toUpperCase(Locale.ROOT);
    }

    private record ModelInfo(List<String> dishes) {}
}
