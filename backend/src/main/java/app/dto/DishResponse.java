package app.dto;

import app.dish.Dish;
import app.price.PriceBook;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

// Public view of a dish. Never return the Dish entity itself: it carries the owner's
// AppUser record, including the password hash.
public record DishResponse(
        Long id,
        String name,
        @Schema(description = "Today's price in S$; null if no price is set") BigDecimal price,
        @Schema(description = "Today's unit cost in S$; null if not set") BigDecimal unitCost
) {
    public static DishResponse from(Dish dish) {
        return new DishResponse(dish.getId(), dish.getName(), null, null);
    }

    public static DishResponse from(Dish dish, PriceBook.Price current) {
        return current == null ? from(dish)
                : new DishResponse(dish.getId(), dish.getName(), current.price(), current.unitCost());
    }
}
