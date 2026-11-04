package app.account;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class SessionExpiryFilterTest {
    @Test
    void expiresAtExactDeadlineWithoutCreatingAnAnonymousSession() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(SessionExpiry.ABSOLUTE_DEADLINE, 1000L);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        var filter = new SessionExpiryFilter(Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> assertNull(request.getSession(false)));
        assertTrue(session.isInvalid());
    }

    @Test
    void legacySessionUsesOriginalCreationTime() throws Exception {
        var session = new MockHttpSession();
        long deadline = session.getCreationTime() + SessionExpiry.DEFAULT_ABSOLUTE_MILLIS;
        assertEquals(deadline, SessionExpiry.deadline(session));
        var request = new MockHttpServletRequest();
        request.setSession(session);
        new SessionExpiryFilter(Clock.fixed(Instant.ofEpochMilli(deadline), ZoneOffset.UTC))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertTrue(session.isInvalid());
    }

    @Test
    void activityDoesNotMoveAbsoluteDeadline() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(SessionExpiry.ABSOLUTE_DEADLINE, 2000L);
        var request = new MockHttpServletRequest();
        request.setSession(session);
        new SessionExpiryFilter(Clock.fixed(Instant.ofEpochMilli(1999), ZoneOffset.UTC))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertFalse(session.isInvalid());
        assertEquals(2000L, SessionExpiry.deadline(session));
    }
}
