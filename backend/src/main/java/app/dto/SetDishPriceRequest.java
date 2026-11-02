package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SetDishPriceRequest(
        @NotNull @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 2)
        @Schema(description = "Selling price of one portion, in S$", example = "4.50") BigDecimal price,
        @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 2)
        @Schema(description = "What one portion costs to make, in S$; optional", example = "1.70") BigDecimal unitCost,
        @Schema(description = "First day the price applies; defaults to today. Earlier sales keep the price that applied then.",
                example = "2026-11-01") LocalDate effectiveFrom
) {}
