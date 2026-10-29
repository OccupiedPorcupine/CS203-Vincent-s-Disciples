package app.controller;

import app.dto.CreateDishRequest;
import app.dto.DishResponse;
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

import java.util.List;

@Tag(name = "Dishes", description = "Dish management operations")
@RestController
@RequestMapping("/api/dishes")
public class DishController {

    private final DishService dishService;

    public DishController(DishService dishService) {
        this.dishService = dishService;
    }

    @Operation(summary = "List dishes", description = "Lists the authenticated user's dishes, sorted by name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dishes listed successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping
    public List<DishResponse> getDishes(@AuthenticationPrincipal AuthenticatedUser user) {
        return dishService.getDishes(user.id()).stream().map(DishResponse::from).toList();
    }

    @Operation(summary = "Create dish", description = "Creates a new dish for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dish created successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid dish data", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PostMapping
    public DishResponse createDish(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateDishRequest request) {
        return DishResponse.from(dishService.createDish(request.name(), user.id()));
    }
}