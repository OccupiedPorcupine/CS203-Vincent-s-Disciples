package app.dto;

import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.media.Schema;

// Stores profile of authenticated users.
public record ProfileResponse(
        @Schema(description = "User ID", example = "1") Long userId,
        @Schema(description = "User email address", example = "user@example.com") String email,
        @Schema(description = "User display name", example = "Jane Doe") String name,
        @Schema(description = "URL of the user's profile picture", example = "https://example.com/profile.jpg") String pictureUrl,
        @Schema(description = "User role", example = "USER") String role
) {
    public static ProfileResponse from(AuthenticatedUser user) {
        return new ProfileResponse(user.id(), user.email(), user.name(), user.pictureUrl(), user.role());
    }
}