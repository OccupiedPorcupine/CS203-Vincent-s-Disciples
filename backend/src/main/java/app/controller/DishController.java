package app.controller;

import app.dish.Dish;
import app.dto.CreateDishRequest;
import app.service.DishService;
import app.user.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dishes")
public class DishController {

    private final DishService dishService;

    public DishController(
            DishService dishService
    ) {
        this.dishService = dishService;
    }

    @PostMapping
    public Dish createDish(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CreateDishRequest request
    ) {
        return dishService.createDish(
                request.name(),
                user.id()
        );
    }
}