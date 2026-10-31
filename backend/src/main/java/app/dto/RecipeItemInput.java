package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RecipeItemInput(
        @NotBlank @Size(max = 60) @Schema(description = "Ingredient name; a new name adds the ingredient", example = "Chicken") String ingredient,
        @NotBlank @Size(max = 20) @Schema(description = "Unit the ingredient is measured in, the same in every dish", example = "g") String unit,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") @Digits(integer = 6, fraction = 3)
        @Schema(description = "Amount one portion uses, in the unit", example = "150") BigDecimal quantity
) {}
