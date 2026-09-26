package app.dto;

public record MlPredictionResponse(
        String dish,
        String forecast_date,
        double predicted_demand
) {}