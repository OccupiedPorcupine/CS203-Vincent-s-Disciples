package app.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ForecastRequest(
        @NotNull @Schema(description = "ID of the dish to forecast", example = "1") Long dishId,
        @NotNull @Schema(description = "Date to generate the forecast for. Must fall within the current NEA 24-hour forecast period.", format = "date") LocalDate forecastDate
) {}