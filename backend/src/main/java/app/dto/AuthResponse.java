package app.dto;

import app.user.AppUser;

public record AuthResponse(
        Long userId,
        String email,
        String name,
        String pictureUrl,
        String role
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
