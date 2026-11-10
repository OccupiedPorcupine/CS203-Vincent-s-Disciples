package app.diagnostics;

// Something the app relies on outside itself (an email account, a service, an API key), checked on
// startup and by GET /api/admin/setup-checks. To cover a new integration, add a @Component that
// implements this next to the integration's code; SetupChecks finds it.
public interface SetupCheck {

    // Short and lower case, e.g. "email".
    String name();

    // Should finish within about 10 seconds. The detail is shown to admins and written to the log,
    // so it must never include a password, key or token.
    Result check();

    enum Status {
        // Set up, and it answered if it can be tried.
        OK,
        // Not set up, and the app works without it.
        OFF,
        // Set up but not working, or missing where it's needed.
        PROBLEM
    }

    record Result(Status status, String detail) {

        public static Result ok(String detail) {
            return new Result(Status.OK, detail);
        }

        public static Result off(String detail) {
            return new Result(Status.OFF, detail);
        }

        public static Result problem(String detail) {
            return new Result(Status.PROBLEM, detail);
        }
    }
}
