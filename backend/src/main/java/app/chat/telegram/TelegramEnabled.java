package app.chat.telegram;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

// The Telegram bot is on only when TELEGRAM_BOT_TOKEN is set, like Google sign-in (#16). Off,
// none of its beans exist: no Connect button, and the webhook is a 404.
public class TelegramEnabled implements Condition {

    static final String TOKEN = "app.telegram.bot-token";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return !context.getEnvironment().getProperty(TOKEN, "").isBlank();
    }
}
