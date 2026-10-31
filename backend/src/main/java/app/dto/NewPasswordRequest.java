package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NewPasswordRequest(
        @NotBlank @Size(max = 100) @Schema(description = "Token from the reset link") String token,
        @NotBlank @Size(min = 12, max = 256) @Schema(description = "New password, at least 12 characters") String password
) {}
