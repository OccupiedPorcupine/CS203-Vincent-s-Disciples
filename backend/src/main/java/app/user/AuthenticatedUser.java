package app.user;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.io.Serializable;

// Keep session state small and independent from the JPA persistence context.
// Context for Spring to define what is stored in an AuthenticatedUser
// Stored serialized in the spring_session_attributes table: changing these fields makes
// existing sessions unreadable, so clear spring_session after deploying such a change
// (see database/README.md, "Login sessions").
public record AuthenticatedUser(
        Long id,
        String googleId,
        String email,
        String name,
        String pictureUrl,
        String role
) implements AuthenticatedPrincipal, Serializable {

    // Spring Security uses this as the login's name, and Spring Session indexes stored
    // sessions by it (principal_name, max 100 chars). Use the stable user ID rather than
    // the default toString(), which would copy the email, name and picture URL into it.
    @Override
    public String getName() {
        return String.valueOf(id);
    }
}

