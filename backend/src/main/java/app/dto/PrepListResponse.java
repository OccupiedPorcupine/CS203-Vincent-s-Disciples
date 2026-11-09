package app.dto;

import app.service.PrepListService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PrepListResponse(
        LocalDate date,
        @Schema(description = "Why the stall is closed that day; the lists are then empty. Null when open.") String closed,
        @Schema(description = "Extra portions on top of the forecast, in percent; null when each dish is prepared by its margin or to the top of its likely range") Integer bufferPercent,
        List<Dish> dishes,
        @Schema(description = "Total of each ingredient for the dishes that have a recipe, by name; perishables are bought for the day, the rest come from stock") List<Ingredient> ingredients,
        @Schema(description = "Dishes sold recently that couldn't be forecast, and why") List<Skipped> skipped
) {
    public record Dish(
            Long dishId,
            String dish,
            @Schema(description = "Portions to prepare", example = "48") int portions,
            @Schema(description = "Forecast portions", example = "42.5") double expected,
            int low,
            int high,
            ForecastResponse.Method method,
            @Schema(description = "How the forecast was worked out", example = "Average of your last 4 Saturdays") String basis,
            boolean hasRecipe,
            @Schema(description = "MARGIN (enough for the share of days its margin makes worthwhile), TOP_OF_RANGE (no price and cost, or asked for) or BUFFER (forecast plus bufferPercent)") PrepListService.Rule rule,
            @Schema(description = "Why this many, for showing to the user", example = "Running out costs you $2.90 a plate and a leftover $1.60, so this is enough on about 64% of days like it.") String why
    ) {}

    public record Ingredient(
            String ingredient,
            String unit,
            @Schema(description = "Amount the portions use, as the recipes give it (cooked)", example = "7200") BigDecimal quantity,
            List<String> dishes,
            @Schema(description = "Cooked or usable weight ÷ purchased weight, in percent", example = "70") BigDecimal yieldPercent,
            @Schema(description = "Amount to buy or take from stock: quantity ÷ yield, rounded up", example = "10285.715") BigDecimal toBuy,
            @Schema(description = "Bought for this day (true), or kept in stock and bought weekly (false)") boolean perishable,
            BigDecimal expectedRawUse
    ) {
        public Ingredient(String ingredient, String unit, BigDecimal quantity, List<String> dishes,
                          BigDecimal yieldPercent, BigDecimal toBuy, boolean perishable) {
            this(ingredient, unit, quantity, dishes, yieldPercent, toBuy, perishable, null);
        }
    }

    public record Skipped(String dish, String reason) {}
}
