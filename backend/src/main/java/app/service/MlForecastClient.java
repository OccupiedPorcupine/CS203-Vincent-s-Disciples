package app.service;

import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MlForecastClient {

    private final RestClient restClient;

    public MlForecastClient() {
        this.restClient = RestClient.builder()
                .baseUrl("http://localhost:8000")
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