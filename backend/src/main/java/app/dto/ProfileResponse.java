package app.dto;

import app.user.AuthenticatedUser;

//stores profile of authenticated users.

public record ProfileResponse(
        Long userId,
        String email,
        String name,
        String pictureUrl,
        String role
) {
    public static ProfileResponse from(AuthenticatedUser user) {
        return new ProfileResponse(
                user.id(),
                user.email(),
                user.name(),
                user.pictureUrl(),
                user.role()
        );
    }
}
