package app.chat.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// The bot's settings (issue #100), from TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_USERNAME and
// TELEGRAM_WEBHOOK_SECRET. See backend/README.md for creating the bot with @BotFather.
@Component
public class TelegramSettings {

    private static final Logger log = LoggerFactory.getLogger(TelegramSettings.class);

    private final String token;
    private final String username;
    private final String webhookSecret;
    private final String apiBaseUrl;
    private final String webhookUrl;

    public TelegramSettings(@Value("${" + TelegramEnabled.TOKEN + ":}") String token,
                            @Value("${app.telegram.bot-username:}") String username,
                            @Value("${app.telegram.webhook-secret:}") String webhookSecret,
                            @Value("${app.telegram.api-base-url:https://api.telegram.org}") String apiBaseUrl,
                            @Value("${app.base-url}") String baseUrl) {
        this.token = token.strip();
        this.username = username.strip().replaceFirst("^@", "");
        this.webhookSecret = webhookSecret.strip();
        this.apiBaseUrl = apiBaseUrl.replaceAll("/+$", "");
        this.webhookUrl = baseUrl.replaceAll("/+$", "") + TelegramWebhookController.PATH;

        if (this.token.isEmpty()) {
            log.warn("Telegram bot is off: TELEGRAM_BOT_TOKEN is not set");
        } else if (this.webhookSecret.isEmpty() || this.username.isEmpty()) {
            log.warn("Telegram bot is on, but TELEGRAM_WEBHOOK_SECRET or TELEGRAM_BOT_USERNAME is not set: "
                    + "every webhook call will be refused and Connect links won't work");
        }
    }

    String token() {
        return token;
    }

    String username() {
        return username;
    }

    String apiBaseUrl() {
        return apiBaseUrl;
    }

    String webhookUrl() {
        return webhookUrl;
    }

    String webhookSecret() {
        return webhookSecret;
    }

    // Whether a webhook call carries our secret, compared in constant time. Always false while
    // no secret is set, so an unconfigured bot can't be fed fake updates.
    boolean isOurSecret(String header) {
        if (webhookSecret.isEmpty() || header == null) {
            return false;
        }
        return MessageDigest.isEqual(webhookSecret.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
    }
}
