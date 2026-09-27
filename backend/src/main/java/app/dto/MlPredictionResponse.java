package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MlPredictionResponse(
        @Schema(description = "Name of the forecasted dish", example = "Chicken Rice") String dish,
        @Schema(description = "Date of the forecast", example = "2026-09-28") String forecast_date,
        @Schema(description = "Predicted demand quantity", example = "42.5") double predicted_demand
) {}