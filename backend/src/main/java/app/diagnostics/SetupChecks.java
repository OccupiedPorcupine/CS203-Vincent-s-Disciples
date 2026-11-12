package app.diagnostics;

import app.diagnostics.SetupCheck.Result;
import app.diagnostics.SetupCheck.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Runs every SetupCheck: once in the background after startup, so a deployment's log says what
// isn't set up, and on demand for GET /api/admin/setup-checks.
@Service
public class SetupChecks {

    private static final Logger log = LoggerFactory.getLogger(SetupChecks.class);

    static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final List<SetupCheck> checks;
    private final boolean checkOnStartup;
    private final Duration timeout;

    @Autowired
    public SetupChecks(List<SetupCheck> checks, @Value("${app.setup-checks.on-startup:true}") boolean checkOnStartup) {
        this(checks, checkOnStartup, TIMEOUT);
    }

    SetupChecks(List<SetupCheck> checks, boolean checkOnStartup, Duration timeout) {
        this.checks = checks.stream().sorted(Comparator.comparing(SetupCheck::name)).toList();
        this.checkOnStartup = checkOnStartup;
        this.timeout = timeout;
    }

    public record Outcome(String name, Status status, String detail) {

        public static Outcome of(String name, Result result) {
            return new Outcome(name, result.status(), result.detail());
        }
    }

    // Runs the checks at the same time, so the slowest one sets how long this takes.
    public List<Outcome> run() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Outcome>> running = checks.stream().map(check -> executor.submit(() -> run(check))).toList();
            Instant deadline = Instant.now().plus(timeout);
            List<Outcome> outcomes = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) {
                outcomes.add(await(checks.get(i), running.get(i), deadline));
            }
            return outcomes;
        } finally {
            // Doesn't wait for a check that is still stuck on a connection.
            executor.shutdownNow();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        if (checkOnStartup) {
            Thread.ofVirtual().name("setup-checks").start(() -> run().forEach(SetupChecks::log));
        }
    }

    private static Outcome run(SetupCheck check) {
        try {
            return Outcome.of(check.name(), check.check());
        } catch (RuntimeException exception) {
            return new Outcome(check.name(), Status.PROBLEM, "The check itself failed: " + exception.getMessage());
        }
    }

    private Outcome await(SetupCheck check, Future<Outcome> outcome, Instant deadline) {
        try {
            long millisLeft = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            return outcome.get(millisLeft, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            return new Outcome(check.name(), Status.PROBLEM, "No answer within " + timeout.toSeconds() + " seconds.");
        } catch (ExecutionException exception) {
            return new Outcome(check.name(), Status.PROBLEM, "The check itself failed: " + exception.getCause());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome(check.name(), Status.PROBLEM, "Interrupted.");
        }
    }

    private static void log(Outcome outcome) {
        if (outcome.status() == Status.PROBLEM) {
            log.warn("Setup check {}: PROBLEM. {}", outcome.name(), outcome.detail());
        } else {
            log.info("Setup check {}: {}. {}", outcome.name(), outcome.status(), outcome.detail());
        }
    }
}
