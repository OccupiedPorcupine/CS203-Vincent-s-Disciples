package app.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

//validating signup email and password formats
//password size here is set to a minimum of 12

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) @Schema(description = "User email address", example = "user@example.com") String email,
        @NotBlank @Size(min = 12, max = 256) @Schema(description = "Password with a minimum of 12 characters", example = "SecurePass123!") String password,
        @NotBlank @Size(max = 100) @Schema(description = "User display name", example = "Jane Doe") String name
) {}
