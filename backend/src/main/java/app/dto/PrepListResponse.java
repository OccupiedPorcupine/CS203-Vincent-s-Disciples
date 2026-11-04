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
            @Schema(description = "MARGIN (enough for the share of days its margin makes worthwhile), TOP_OF_RANGE (no price and cost, or asked for) or BUFFER (forecast plus bufferPercent)") PrepListService.Rule rule,
            @Schema(description = "Why this many, for showing to the user", example = "Running out costs you $2.90 a plate and a leftover $1.60, so this is enough on about 64% of days like it.") String why
    ) {}

    public record Ingredient(String ingredient, String unit, BigDecimal quantity, List<String> dishes) {}

    public record Skipped(String dish, String reason) {}
}
