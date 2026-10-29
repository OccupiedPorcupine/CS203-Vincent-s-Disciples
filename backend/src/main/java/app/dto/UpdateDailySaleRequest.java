package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateDailySaleRequest(
        @NotNull @PositiveOrZero @Schema(description = "Quantity of the dish sold that day", example = "45") Integer quantitySold
) {}
