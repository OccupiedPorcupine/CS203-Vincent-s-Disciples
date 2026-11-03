package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PrepListResponse(
        LocalDate date,
        @Schema(description = "Why the stall is closed that day; the lists are then empty. Null when open.") String closed,
        @Schema(description = "The rule asked for. MARGIN falls back to RANGE for a dish without a price and cost; each dish says which it got") Rule rule,
        @Schema(description = "Extra portions on top of the forecast, in percent, when the rule is BUFFER; null otherwise") Integer bufferPercent,
        List<Dish> dishes,
        @Schema(description = "Total of each ingredient for the dishes that have a recipe, by name") List<Ingredient> ingredients,
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
            @Schema(description = "How the portions were chosen for this dish") Rule rule,
            @Schema(description = "Why, when the rule needs explaining; null otherwise",
                    example = "Enough for about 64% of days: running out loses $2.90 profit a plate, a leftover wastes $1.60.") String reason
    ) {}

    // MARGIN: as many portions as are worth the risk, from the dish's price and cost (#86).
    // RANGE: the top of the likely range. BUFFER: the forecast plus bufferPercent.
    public enum Rule { MARGIN, RANGE, BUFFER }

    public record Ingredient(String ingredient, String unit, BigDecimal quantity, List<String> dishes) {}

    public record Skipped(String dish, String reason) {}
}
