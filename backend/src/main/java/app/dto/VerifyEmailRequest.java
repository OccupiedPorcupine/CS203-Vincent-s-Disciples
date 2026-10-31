package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
        @NotBlank @Size(max = 100) @Schema(description = "Token from the verification link") String token
) {}
