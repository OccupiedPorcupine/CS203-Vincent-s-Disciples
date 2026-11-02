package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SalesImportRequest(
        @NotNull @Schema(description = "What to do with dates that already have sales") ExistingSales existingSales,
        @Size(max = 500)
        @Schema(description = "Choices for names in the file. Needed for every name that matches no dish; "
                + "names that match a dish use it unless a choice says otherwise.")
        List<@Valid DishChoice> dishes
) {
    public enum ExistingSales { SKIP, OVERWRITE }

    public enum Action { EXISTING, NEW, LEAVE_OUT }

    public record DishChoice(
            @NotBlank @Size(max = 100) @Schema(description = "Name as written in the file") String name,
            @NotNull Action action,
            @Schema(description = "For EXISTING: the dish to record these rows under") Long dishId,
            @Size(max = 100) @Schema(description = "For NEW: the new dish's name; defaults to the name in the file") String newDishName
    ) {}
}
