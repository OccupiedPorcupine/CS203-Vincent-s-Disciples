package app.dto;

import app.dish.DishAlias;
import io.swagger.v3.oas.annotations.media.Schema;

public record DishAliasResponse(
        @Schema(description = "Alias ID", example = "5") Long id,
        @Schema(description = "Another name for the dish, as entered", example = "鸡饭") String alias
) {
    public static DishAliasResponse from(DishAlias alias) {
        return new DishAliasResponse(alias.getId(), alias.getAlias());
    }
}
