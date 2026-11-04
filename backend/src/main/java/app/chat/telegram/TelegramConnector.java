package app.chat.telegram;

import app.chat.ChatChannel;
import app.chat.ChatConnector;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

// Telegram as a chat channel: a Connect link opens the bot with the code as its start parameter,
// and Telegram sends it back as "/start <code>" (TelegramUpdateHandler).
@Component
@Conditional(TelegramEnabled.class)
public class TelegramConnector implements ChatConnector {

    private final TelegramSettings settings;

    public TelegramConnector(TelegramSettings settings) {
        this.settings = settings;
    }

    @Override
    public ChatChannel channel() {
        return ChatChannel.TELEGRAM;
    }

    @Override
    public String linkFor(String code) {
        return "https://t.me/" + settings.username() + "?start=" + code;
    }
}
