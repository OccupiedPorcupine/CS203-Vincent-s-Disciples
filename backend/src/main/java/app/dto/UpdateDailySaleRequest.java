package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalTime;

// Replaces the day's record: leaving out soldOutAt or leftoverQuantity clears it.
public record UpdateDailySaleRequest(
        @NotNull @PositiveOrZero @Max(AddDailySalesRequest.MAX_QUANTITY) @Schema(description = "Quantity of the dish sold that day", example = "45") Integer quantitySold,
        @Schema(type = "string", description = "When the dish sold out, 24-hour; null if it didn't", example = "12:40") LocalTime soldOutAt,
        @PositiveOrZero @Max(AddDailySalesRequest.MAX_QUANTITY) @Schema(description = "Portions prepared but not sold; null if not recorded", example = "3") Integer leftoverQuantity
) {}
