package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDishRequest(
        @NotBlank @Size(max = 100) @Schema(description = "Name of the dish", example = "Chicken Rice") String name
) {}