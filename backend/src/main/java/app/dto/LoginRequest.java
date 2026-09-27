package app.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) @Schema(description = "User email address", example = "user@example.com") String email,
        @NotBlank @Size(max = 256) @Schema(description = "User password", example = "SecurePass123!") String password
) {}