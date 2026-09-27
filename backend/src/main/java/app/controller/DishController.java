package app.controller;

import app.dish.Dish;
import app.dto.CreateDishRequest;
import app.service.DishService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Dishes", description = "Dish management operations")
@RestController
@RequestMapping("/api/dishes")
public class DishController {

    private final DishService dishService;

    public DishController(DishService dishService) {
        this.dishService = dishService;
    }

    @Operation(summary = "Create dish", description = "Creates a new dish for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dish created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid dish data", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PostMapping
    public Dish createDish(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateDishRequest request) {
        return dishService.createDish(request.name(), user.id());
    }
}