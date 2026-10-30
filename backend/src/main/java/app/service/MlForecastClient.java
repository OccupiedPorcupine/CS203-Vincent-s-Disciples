package app.service;

import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MlForecastClient {

    private final RestClient restClient;

    public MlForecastClient(@Value("${app.ml.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                // Strip a trailing slash so the base URL joins cleanly with "/predict".
                .baseUrl(baseUrl.replaceAll("/+$", ""))
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
}
