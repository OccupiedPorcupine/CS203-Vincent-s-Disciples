package app.account;

import app.user.AppUser;
import jakarta.servlet.http.HttpSession;

/** Limits are snapshotted at authentication; preference changes never extend an existing login. */
public final class SessionExpiry {
    public static final String ABSOLUTE_DEADLINE = "chicky.session.absoluteDeadline";
    public static final long DEFAULT_ABSOLUTE_MILLIS = 8 * 60 * 60 * 1000L;

    private SessionExpiry() {}

    public static void start(HttpSession session, AppUser user, long now) {
        session.setMaxInactiveInterval(user.getSessionIdleMinutes() * 60);
        session.setAttribute(ABSOLUTE_DEADLINE, now + user.getSessionAbsoluteMinutes() * 60_000L);
    }

    public static long deadline(HttpSession session) {
        Object stored = session.getAttribute(ABSOLUTE_DEADLINE);
        // Sessions issued before this release cannot silently live forever. Use their original
        // creation time rather than giving them a fresh eight hours on the first request.
        return stored instanceof Long value ? value : session.getCreationTime() + DEFAULT_ABSOLUTE_MILLIS;
    }
}
