package app.controller;

import app.dto.CreateDishRequest;
import app.dto.DishResponse;
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
    public DishResponse createDish(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CreateDishRequest request
    ) {
        return DishResponse.from(dishService.createDish(
                request.name(),
                user.id()
        ));
    }
}