package app.chat;

import app.diagnostics.SetupCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// The hourly GitHub Action authenticates with CRON_SECRET (see ChatJobController). Whether the
// Action calls this host or the other one is set in the repository, so it can't be checked here.
@Component
public class ChatRemindersSetupCheck implements SetupCheck {

    private final String cronSecret;

    public ChatRemindersSetupCheck(@Value("${app.chat.cron-secret:}") String cronSecret) {
        this.cronSecret = cronSecret.strip();
    }

    @Override
    public String name() {
        return "chat reminders";
    }

    @Override
    public Result check() {
        return cronSecret.isEmpty()
                ? Result.off("CRON_SECRET is not set, so the hourly reminders job is refused and no evening reminders go out.")
                : Result.ok("CRON_SECRET is set. Reminders go out when the GitHub Action calls this host.");
    }
}
