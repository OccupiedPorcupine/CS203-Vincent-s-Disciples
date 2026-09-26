package app.user;

import java.io.Serializable;

// Keep session state small and independent from the JPA persistence context.
// Context for Spring to define what is stored in an AuthenticatedUser
public record AuthenticatedUser(
        Long id,
        String googleId,
        String email,
        String name,
        String pictureUrl,
        String role
) implements Serializable {
}

