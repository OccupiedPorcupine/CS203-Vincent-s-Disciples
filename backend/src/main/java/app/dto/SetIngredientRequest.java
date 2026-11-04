package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

public record SetIngredientRequest(
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("500") @Digits(integer = 3, fraction = 1)
        @Schema(description = "Cooked or usable weight ÷ purchased weight, in percent; null for the default", example = "70") BigDecimal yieldPercent,
        @Schema(description = "Bought for each day (true) or kept in stock (false); null for the default") Boolean perishable
) {}
