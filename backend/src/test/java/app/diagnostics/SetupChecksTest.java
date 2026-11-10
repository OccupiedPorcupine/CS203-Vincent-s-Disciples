package app.diagnostics;

import app.diagnostics.SetupCheck.Result;
import app.diagnostics.SetupCheck.Status;
import app.diagnostics.SetupChecks.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A check that throws or hangs shows as a PROBLEM and doesn't hold up the others.
class SetupChecksTest {

    @Test
    void outcomesComeBackByNameWhateverEachCheckDoes() {
        SetupChecks checks = new SetupChecks(List.of(
                check("telegram", () -> Result.off("No token.")),
                check("email", () -> { throw new IllegalStateException("boom"); }),
                check("ml", () -> {
                    sleep(Duration.ofSeconds(5));
                    return Result.ok("Too late.");
                }),
                check("assistant", () -> Result.ok("Fine."))
        ), false, Duration.ofMillis(300));

        long started = System.nanoTime();
        List<Outcome> outcomes = checks.run();
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(List.of("assistant", "email", "ml", "telegram"), outcomes.stream().map(Outcome::name).toList());
        assertEquals(new Outcome("assistant", Status.OK, "Fine."), outcomes.get(0));
        assertEquals(Status.PROBLEM, outcomes.get(1).status());
        assertTrue(outcomes.get(1).detail().contains("boom"), outcomes.get(1).detail());
        assertEquals(Status.PROBLEM, outcomes.get(2).status());
        assertTrue(outcomes.get(2).detail().startsWith("No answer within"), outcomes.get(2).detail());
        assertEquals(Status.OFF, outcomes.get(3).status());
        assertTrue(tookMillis < 2_000, "the hung check held everything up for " + tookMillis + " ms");
    }

    private static SetupCheck check(String name, Supplier<Result> result) {
        return new SetupCheck() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Result check() {
                return result.get();
            }
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
