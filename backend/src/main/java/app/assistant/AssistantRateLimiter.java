package app.assistant;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Limits each user's assistant requests, which cost money per call. In memory, per backend instance.
@Component
public class AssistantRateLimiter {

    static final int MAX_REQUESTS = 20;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<Long, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private final Clock clock;

    public AssistantRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(Long ownerId) {
        Instant now = clock.instant();
        Deque<Instant> recent = requests.computeIfAbsent(ownerId, id -> new ArrayDeque<>());

        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= MAX_REQUESTS) {
                throw new AssistantException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many assistant requests. Please wait a minute.");
            }
            recent.addLast(now);
        }
    }
}
