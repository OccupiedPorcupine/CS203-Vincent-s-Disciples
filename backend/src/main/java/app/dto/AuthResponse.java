package app.dto;

import app.user.AppUser;
import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponse(
        @Schema(description = "User ID", example = "1") Long userId,
        @Schema(description = "User email address", example = "user@example.com") String email,
        @Schema(description = "User display name", example = "Jane Doe") String name,
        @Schema(description = "URL of the user's profile picture", example = "https://example.com/profile.jpg") String pictureUrl,
        @Schema(description = "User role", example = "USER") String role
) {
    public static AuthResponse from(AppUser user) {
        return new AuthResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPictureUrl(),
                user.getRole()
        );
    }
}
