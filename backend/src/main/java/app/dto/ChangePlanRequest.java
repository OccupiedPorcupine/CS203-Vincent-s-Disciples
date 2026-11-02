package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ChangePlanRequest(
        @NotBlank @Schema(description = "FREE, STANDARD or PRO", example = "STANDARD") String plan
) {}
