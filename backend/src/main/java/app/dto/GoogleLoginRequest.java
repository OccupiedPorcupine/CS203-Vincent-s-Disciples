package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record GoogleLoginRequest(
        @NotBlank @Schema(description = "Google ID token credential returned by Google Sign-In", example = "eyJhbGciOiJSUzI1NiIs...")
        String credential
) {}