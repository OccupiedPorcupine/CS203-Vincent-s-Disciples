package app.controller;

import app.price.DishPriceService;
import app.price.PriceBook;
import java.time.Clock;
import java.time.LocalDate;
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
    private final DishPriceService priceService;
    private final Clock clock;

    public DishController(DishService dishService, DishPriceService priceService, Clock clock) {
        this.dishService = dishService;
        this.priceService = priceService;
        this.clock = clock;
    }

    @Operation(summary = "List dishes", description = "Lists the authenticated user's dishes, sorted by name, with today's price and cost.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dishes listed successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping
    public List<DishResponse> getDishes(@AuthenticationPrincipal AuthenticatedUser user) {
        PriceBook prices = priceService.priceBook(user.id());
        LocalDate today = LocalDate.now(clock);
        return dishService.getDishes(user.id()).stream()
                .map(dish -> DishResponse.from(dish, prices.on(dish.getId(), today).orElse(null)))
                .toList();
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