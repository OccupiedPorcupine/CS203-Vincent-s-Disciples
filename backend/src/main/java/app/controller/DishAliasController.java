package app.controller;

import app.dto.CreateDishAliasRequest;
import app.dto.DishAliasResponse;
import app.service.DishAliasService;
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
@RequestMapping("/api/dishes/{dishId}/aliases")
public class DishAliasController {

    private final DishAliasService dishAliasService;

    public DishAliasController(DishAliasService dishAliasService) {
        this.dishAliasService = dishAliasService;
    }

    @Operation(summary = "List dish aliases", description = "Lists the other names the authenticated user uses for one of their dishes.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Aliases listed successfully"),
            @ApiResponse(responseCode = "400", description = "Dish not found", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping
    public List<DishAliasResponse> getAliases(@PathVariable Long dishId, @AuthenticationPrincipal AuthenticatedUser user) {
        return dishAliasService.getAliases(dishId, user.id()).stream().map(DishAliasResponse::from).toList();
    }

    @Operation(summary = "Add dish alias",
            description = "Adds another name for a dish, e.g. a Chinese name or nickname. Matching ignores case and extra spaces. "
                    + "Adding a name the dish already has returns the existing alias.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alias added, or it already existed for this dish"),
            @ApiResponse(responseCode = "400", description = "Invalid alias or dish not found", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user already uses this name for another dish", content = @Content)
    })
    @PostMapping
    public DishAliasResponse addAlias(
            @PathVariable Long dishId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateDishAliasRequest request
    ) {
        return DishAliasResponse.from(dishAliasService.addAlias(dishId, user.id(), request.alias()));
    }
}
