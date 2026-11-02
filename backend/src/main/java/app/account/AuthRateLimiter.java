package app.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Slows down password guessing, mass signups and reset-email floods: each limit allows a number
// of attempts per IP address or per email within a window. In memory, per backend instance, so a
// restart clears it; per-email limits still hold whatever IP an attacker uses.
@Component
public class AuthRateLimiter {

    public enum Limit {
        LOGIN_PER_EMAIL(10, Duration.ofMinutes(15)),
        // Per-IP limits are generous: phones on mobile data often share one address.
        LOGIN_PER_IP(100, Duration.ofMinutes(15)),
        SIGNUP_PER_IP(20, Duration.ofHours(1)),
        RESET_PER_EMAIL(3, Duration.ofHours(1)),
        RESET_PER_IP(10, Duration.ofHours(1)),
        TOKEN_PER_IP(30, Duration.ofHours(1)),
        VERIFICATION_PER_USER(3, Duration.ofHours(1));

        final int max;
        final Duration window;

        Limit(int max, Duration window) {
            this.max = max;
            this.window = window;
        }
    }

    // Stale keys are swept every this many checks, so the map doesn't grow without bound.
    private static final int SWEEP_EVERY = 1000;

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final AtomicInteger checks = new AtomicInteger();
    private final Clock clock;
    private final boolean enabled;

    // Tests turn the limits off (app.auth.rate-limits.enabled=false): they share one instance
    // and sign up many accounts from one address.
    public AuthRateLimiter(Clock clock, @Value("${app.auth.rate-limits.enabled:true}") boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    // Counts an attempt, or throws if the limit is already reached.
    public void check(Limit limit, String key) {
        if (!enabled) {
            return;
        }
        Instant now = clock.instant();
        if (checks.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep(now);
        }

        Deque<Instant> recent = attempts.computeIfAbsent(limit + ":" + key.toLowerCase(Locale.ROOT), k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, now.minus(limit.window));
            if (recent.size() >= limit.max) {
                Duration retryAfter = Duration.between(now, recent.peekFirst().plus(limit.window));
                long minutes = Math.max(1, (retryAfter.toSeconds() + 59) / 60);
                throw new TooManyRequestsException("Too many attempts. Try again in " + minutes
                        + (minutes == 1 ? " minute." : " minutes."), retryAfter);
            }
            recent.addLast(now);
        }
    }

    private void sweep(Instant now) {
        Instant longest = now.minus(Duration.ofHours(1));
        attempts.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), longest);
                return entry.getValue().isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> recent, Instant cutoff) {
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.pollFirst();
        }
    }
}
