package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDishAliasRequest(
        @NotBlank @Size(max = 100) @Schema(description = "Another name for the dish", example = "鸡饭") String alias
) {}
