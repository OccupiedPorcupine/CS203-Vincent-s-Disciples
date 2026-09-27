package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record CreateDishRequest(
        @NotBlank @Schema(description = "Name of the dish", example = "Chicken Rice") String name
) {}