package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Component
public class MlClient {
    private final RestClient client;
    public MlClient(RestClient mlRestClient) { this.client = mlRestClient; }

    public ValidationResponse validate(Path path) {
        return client.post().uri("/internal/datasets/validate").body(new ValidationRequest(path.toString()))
            .retrieve().body(ValidationResponse.class);
    }

    public TrainingResponse train(UUID runId, List<String> paths) {
        return client.post().uri("/internal/models/train").body(new TrainingRequest(runId, paths))
            .retrieve().body(TrainingResponse.class);
    }

    public ForecastResponse forecast(ForecastRequest request) {
        return client.post().uri("/internal/models/predict").body(request)
            .retrieve().body(ForecastResponse.class);
    }
}
