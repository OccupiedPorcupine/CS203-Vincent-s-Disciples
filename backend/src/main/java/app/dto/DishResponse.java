package app.dto;

import app.dish.Dish;

// Public view of a dish. Never return the Dish entity itself: it carries the owner's
// AppUser record, including the password hash.
public record DishResponse(Long id, String name) {
    public static DishResponse from(Dish dish) {
        return new DishResponse(dish.getId(), dish.getName());
    }
}
