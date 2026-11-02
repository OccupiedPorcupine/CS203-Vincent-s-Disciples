package app.assistant;

import java.util.Locale;

// An account's plan, which sets its daily assistant allowances (issue #65). Stored by name in
// app_users.plan; the allowances themselves are in application.properties (AssistantPlans).
public enum AssistantPlan {
    FREE, STANDARD, PRO;

    // Unknown or missing names fall back to FREE, so a typo in the database never lifts a limit.
    public static AssistantPlan of(String name) {
        if (name == null) {
            return FREE;
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return FREE;
        }
    }
}
