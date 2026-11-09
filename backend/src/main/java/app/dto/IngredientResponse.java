package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IngredientResponse(
        Long id,
        String name,
        String unit,
        @Schema(description = "Cooked or usable weight ÷ purchased weight, in percent; the amount to buy is the recipe's amount ÷ this", example = "75") BigDecimal yieldPercent,
        @Schema(description = "The yield used when the owner hasn't set one: USDA's for this name, or 100", example = "75") BigDecimal defaultYieldPercent,
        @Schema(description = "Where the default yield comes from; null when it is 100% for want of one", example = "USDA cooking yield for whole chicken with skin, simmered") String defaultYieldBasis,
        @Schema(description = "Bought for each day (true) or kept in stock and bought weekly (false)") boolean perishable,
        @Schema(description = "Whether the owner set the yield or perishable themselves rather than using the defaults") boolean customised,
        BigDecimal stockQuantity,
        LocalDate stockCountedOn,
        LocalDate stockExpiresOn
) {}
