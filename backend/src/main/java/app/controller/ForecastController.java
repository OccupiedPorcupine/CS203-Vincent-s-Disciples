package app.controller;

import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import app.service.MlForecastClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final MlForecastClient mlForecastClient;

    public ForecastController(MlForecastClient mlForecastClient) {
        this.mlForecastClient = mlForecastClient;
    }

    @PostMapping("/predict")
    public MlPredictionResponse predict(
            @RequestBody MlPredictionRequest request
    ) {
        return mlForecastClient.predict(request);
    }
}