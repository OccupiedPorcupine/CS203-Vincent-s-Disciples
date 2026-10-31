package app.controller;

import app.dto.IngredientResponse;
import app.dto.PrepListResponse;
import app.dto.RecipeResponse;
import app.dto.SetRecipeRequest;
import app.recipe.RecipeService;
import app.service.PrepListService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Prep list", description = "Recipes, and the portions and ingredients to prepare for a day")
@RestController
@RequestMapping("/api")
public class RecipeController {

    private final RecipeService recipeService;
    private final PrepListService prepListService;

    public RecipeController(RecipeService recipeService, PrepListService prepListService) {
        this.recipeService = recipeService;
        this.prepListService = prepListService;
    }

    @Operation(summary = "List recipes", description = "Every dish, sorted by name, with what one portion uses. Dishes without a recipe have no items.")
    @GetMapping("/recipes")
    public List<RecipeResponse> recipes(@AuthenticationPrincipal AuthenticatedUser user) {
        return recipeService.recipes(user.id());
    }

    @Operation(summary = "Set a dish's recipe", description = "Replaces what one portion of the dish uses. A new ingredient name adds the "
            + "ingredient; an ingredient no recipe uses any more is removed. An ingredient has one unit in every dish.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recipe saved"),
            @ApiResponse(responseCode = "400", description = "Unknown dish, an ingredient listed twice, or a unit that differs from another dish's", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PutMapping("/dishes/{dishId}/recipe")
    public RecipeResponse setRecipe(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long dishId,
                                    @Valid @RequestBody SetRecipeRequest request) {
        return recipeService.setRecipe(user.id(), dishId, request.items());
    }

    @Operation(summary = "List ingredients", description = "Every ingredient used in a recipe, with its unit, sorted by name.")
    @GetMapping("/ingredients")
    public List<IngredientResponse> ingredients(@AuthenticationPrincipal AuthenticatedUser user) {
        return recipeService.ingredients(user.id());
    }

    @Operation(summary = "Prep list for a day", description = "Portions to prepare of each dish sold in the last 28 days, and the ingredients they need. "
            + "Portions are the top of the forecast's likely range, or the forecast plus bufferPercent when it is given.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prep list worked out; 'closed' is set instead when the stall is closed that day"),
            @ApiResponse(responseCode = "400", description = "Date not from today up to 7 days ahead, or buffer not 0 to 100", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping("/prep-list")
    public PrepListResponse prepList(@AuthenticationPrincipal AuthenticatedUser user,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                     @RequestParam(required = false) Integer bufferPercent) {
        return prepListService.prepList(user.id(), date, bufferPercent);
    }
}
