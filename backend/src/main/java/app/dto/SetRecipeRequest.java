package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SetRecipeRequest(
        @NotNull @Size(max = 30) @Schema(description = "Every ingredient of one portion; an empty list clears the recipe")
        List<@Valid @NotNull RecipeItemInput> items
) {}
