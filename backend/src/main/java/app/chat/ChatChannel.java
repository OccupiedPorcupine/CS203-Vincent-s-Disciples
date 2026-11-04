package app.chat;

import java.util.Locale;
import java.util.Optional;

// Chat apps an owner can link to their account (issue #103). Each is added by a ChatConnector.
// Telegram only: WhatsApp (#102) was dropped.
public enum ChatChannel {
    TELEGRAM("Telegram");

    private final String label;

    ChatChannel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    // "telegram" in a URL is TELEGRAM.
    public static Optional<ChatChannel> fromPath(String value) {
        for (ChatChannel channel : values()) {
            if (channel.name().equals(value.toUpperCase(Locale.ROOT))) return Optional.of(channel);
        }
        return Optional.empty();
    }
}
