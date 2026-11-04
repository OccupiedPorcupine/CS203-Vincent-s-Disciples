package app.chat.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Registers the webhook with Telegram (issue #100). Run once per environment after setting the
// bot's variables, not on every startup: POST /api/admin/telegram/webhook as an admin.
@Tag(name = "Admin")
@RestController
@Conditional(TelegramEnabled.class)
public class TelegramAdminController {

    private final TelegramSettings settings;
    private final TelegramBotClient bot;

    public TelegramAdminController(TelegramSettings settings, TelegramBotClient bot) {
        this.settings = settings;
        this.bot = bot;
    }

    @Operation(summary = "Register the Telegram webhook", description = "Points the bot at this server's "
            + TelegramWebhookController.PATH + " (from APP_BASE_URL) with TELEGRAM_WEBHOOK_SECRET, and sets the bot's command menu.")
    @PostMapping("/api/admin/telegram/webhook")
    public Map<String, String> registerWebhook() {
        if (settings.webhookSecret().isEmpty()) {
            throw new IllegalArgumentException("Set TELEGRAM_WEBHOOK_SECRET first.");
        }
        bot.setWebhook(settings.webhookUrl(), settings.webhookSecret());
        bot.setCommands(TelegramUpdateHandler.COMMANDS);
        return Map.of("webhook", settings.webhookUrl());
    }
}
