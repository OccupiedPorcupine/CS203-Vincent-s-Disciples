package app.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

public record ForecastResponse(
        @Schema(description = "Name of the forecasted dish", example = "Chicken rice") String dish,
        @Schema(description = "Date of the forecast", format = "date") LocalDate forecastDate,
        @Schema(description = "Expected portions, never negative", example = "42.5") double predictedDemand,
        @Schema(description = "Low end of the likely range, in portions", example = "36") int low,
        @Schema(description = "High end of the likely range, in portions", example = "49") int high,
        @Schema(description = "True when there were too few past days to measure the range, so it is a rough +/- 25%") boolean roughRange,
        @Schema(description = "MODEL (the trained forecast model) or BASELINE (an average of the dish's own past sales)") Method method,
        @Schema(description = "Why the baseline was used; null when the model was used") FallbackReason fallbackReason,
        @Schema(description = "How the number was worked out, for showing to the user", example = "Average of your last 4 Saturdays") String basis,
        @Schema(description = "Why the model wasn't used, for showing to the user; null when it was used") String note
) {
    public enum Method { MODEL, BASELINE }

    public enum FallbackReason { SHORT_HISTORY, DISH_NOT_IN_MODEL, WEATHER_UNAVAILABLE, MODEL_UNAVAILABLE }
}
