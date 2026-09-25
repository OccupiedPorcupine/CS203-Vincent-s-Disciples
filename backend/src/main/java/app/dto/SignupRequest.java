package app.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

//validating signup email and password formats
//password size here is set to a minimum of 12

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 256) String password,
        @NotBlank @Size(max = 100) String name
) {
}
