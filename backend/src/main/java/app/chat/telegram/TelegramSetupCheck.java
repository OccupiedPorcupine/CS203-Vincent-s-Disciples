package app.chat.telegram;

import app.diagnostics.SetupCheck;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

// Asks Telegram which bot the token belongs to and where it sends updates. A bot has one webhook,
// so with two hosts (EC2 and Vercel) only one of them gets the chat messages.
@Component
public class TelegramSetupCheck implements SetupCheck {

    private final TelegramSettings settings;
    private final ObjectProvider<TelegramBotClient> bot;

    public TelegramSetupCheck(TelegramSettings settings, ObjectProvider<TelegramBotClient> bot) {
        this.settings = settings;
        this.bot = bot;
    }

    @Override
    public String name() {
        return "telegram";
    }

    @Override
    public Result check() {
        if (settings.token().isEmpty()) {
            return Result.off("TELEGRAM_BOT_TOKEN is not set, so the Telegram bot is off.");
        }
        if (settings.webhookSecret().isEmpty() || settings.username().isEmpty()) {
            return Result.problem("TELEGRAM_WEBHOOK_SECRET or TELEGRAM_BOT_USERNAME is not set, so every webhook call "
                    + "is refused and Connect links don't work.");
        }
        String username;
        String webhook;
        try {
            username = bot.getObject().botUsername();
            webhook = bot.getObject().webhookUrl();
        } catch (TelegramException exception) {
            return Result.problem(exception.getMessage() + ". Check TELEGRAM_BOT_TOKEN.");
        }
        if (!username.equalsIgnoreCase(settings.username())) {
            return Result.problem("The token belongs to @%s, but TELEGRAM_BOT_USERNAME is %s, so Connect links open the wrong bot."
                    .formatted(username, settings.username()));
        }
        if (webhook.isEmpty()) {
            return Result.problem("@%s has no webhook, so it gets no messages. Register it with POST /api/admin/telegram/webhook."
                    .formatted(username));
        }
        if (!webhook.equals(settings.webhookUrl())) {
            return Result.problem(("@%s sends messages to %s, not this host (%s). That's expected if the other host is the live one; "
                    + "otherwise register it here with POST /api/admin/telegram/webhook.").formatted(username, webhook, settings.webhookUrl()));
        }
        return Result.ok("@%s answers and sends its messages to this host.".formatted(username));
    }
}
