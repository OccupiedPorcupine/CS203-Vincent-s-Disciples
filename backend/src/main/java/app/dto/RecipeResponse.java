package app.dto;

import java.math.BigDecimal;
import java.util.List;

public record RecipeResponse(Long dishId, String dish, List<Item> items) {

    public record Item(String ingredient, String unit, BigDecimal quantity) {}
}
