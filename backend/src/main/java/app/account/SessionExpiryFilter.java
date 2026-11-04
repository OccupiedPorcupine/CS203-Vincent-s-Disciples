package app.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;

/** Runs before SecurityContextHolderFilter, so expired authentication is never loaded. */
public class SessionExpiryFilter extends OncePerRequestFilter {
    private final Clock clock;

    public SessionExpiryFilter(Clock clock) { this.clock = clock; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && clock.millis() >= SessionExpiry.deadline(session)) {
            session.invalidate();
        }
        // Public login/CSRF endpoints remain usable with an expired cookie. Protected endpoints
        // get the normal 401 from Spring Security. Spring Session expires the invalidated cookie.
        chain.doFilter(request, response);
    }
}
