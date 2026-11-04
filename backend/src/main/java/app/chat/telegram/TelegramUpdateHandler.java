package app.chat.telegram;

import app.chat.ChatChannel;
import app.chat.ChatCommands;
import app.chat.ChatInput;
import app.chat.ChatLinkService;
import app.chat.ChatMessageService;
import app.chat.ChatReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns Telegram updates into calls to the shared chat services (issue #100) and their replies
// back into Telegram messages. Handles private chats only; group chats wait for staff roles (#58).
// An update Telegram resends (it does when we are slow, e.g. on a cold start) is handled once.
@Component
@Conditional(TelegramEnabled.class)
public class TelegramUpdateHandler {

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdateHandler.class);

    static final ChatChannel CHANNEL = ChatChannel.TELEGRAM;
    static final String CONFIRM = "confirm:";
    static final String CANCEL = "cancel:";
    // The same limits as the web mic: a minute, and AssistantService's 5 MB.
    static final int MAX_VOICE_SECONDS = 60;
    static final long MAX_VOICE_BYTES = 5L * 1024 * 1024;

    private static final Pattern COMMAND = Pattern.compile("^/([a-z_]+)(?:@\\S+)?(?:\\s+(.*))?$", Pattern.DOTALL);
    private static final Pattern CALLBACK = Pattern.compile("^(confirm|cancel):(\\d{1,18})$");

    static final List<TelegramBotClient.Command> COMMANDS = List.of(
            new TelegramBotClient.Command("today", "Sales recorded today"),
            new TelegramBotClient.Command("prep", "What to prepare tomorrow"),
            new TelegramBotClient.Command("forecast", "Tomorrow's forecast"),
            new TelegramBotClient.Command("help", "What I can do"),
            new TelegramBotClient.Command("unlink", "Disconnect this chat from Chicky"));

    static final String HELP = """
            I'm Chicky's assistant. Send me your sales as a message or a voice note, like "chicken rice 42, char siew 30" \
            or "closed tomorrow", then tap Confirm to save them. You can also ask what to prepare or why a forecast is what it is.

            /today – sales recorded today
            /prep – what to prepare tomorrow
            /forecast – tomorrow's forecast
            /unlink – disconnect this chat from Chicky""";

    static final String LINKED = "Linked to your Chicky account. Send me your sales any time, like \"chicken rice 42, char siew 30\".";
    static final String LINK_FAILED = "That link has expired or was already used. In Chicky, open Account, then Chat apps, "
            + "and tap Connect for a new one.";

    private final TelegramBotClient bot;
    private final ChatMessageService messages;
    private final ChatLinkService links;
    private final ChatCommands commands;

    public TelegramUpdateHandler(TelegramBotClient bot, ChatMessageService messages, ChatLinkService links, ChatCommands commands) {
        this.bot = bot;
        this.messages = messages;
        this.links = links;
        this.commands = commands;
    }

    public void handle(JsonNode update) {
        if (!messages.firstDelivery(CHANNEL, update.path("update_id").asString())) {
            log.info("Telegram update {} was already handled", update.path("update_id").asString());
            return;
        }
        if (update.has("callback_query")) {
            callback(update.path("callback_query"));
        } else if (update.has("message")) {
            message(update.path("message"));
        }
    }

    private void message(JsonNode message) {
        JsonNode chat = message.path("chat");
        if (!"private".equals(chat.path("type").asString())) {
            return;
        }
        String chatId = chat.path("id").asString();
        try {
            if (message.has("text")) {
                text(chatId, message.path("text").asString().strip(), name(message.path("from")));
            } else if (message.has("voice")) {
                voice(chatId, message.path("voice"));
            } else {
                bot.sendMessage(chatId, "I can read messages and voice notes. " + HELP.lines().findFirst().orElse(""), null);
            }
        } catch (RuntimeException exception) {
            log.warn("Telegram message in chat {} failed: {}", chatId, exception.toString());
            bot.sendMessage(chatId, "Sorry, something went wrong. Please try again.", null);
        }
    }

    private void text(String chatId, String text, String name) {
        Matcher command = COMMAND.matcher(text);
        if (!command.matches()) {
            reply(chatId, messages.handle(CHANNEL, chatId, new ChatInput.Text(text)), false);
            return;
        }
        String argument = command.group(2) == null ? "" : command.group(2).strip();
        String answer = switch (command.group(1)) {
            case "start" -> start(chatId, argument, name);
            case "unlink" -> links.unlinkChat(CHANNEL, chatId)
                    ? "Unlinked. This chat can no longer see or record anything in Chicky."
                    : "This chat isn't linked to a Chicky account.";
            case "today" -> commands.today(CHANNEL, chatId);
            case "prep" -> commands.prep(CHANNEL, chatId);
            case "forecast" -> commands.forecast(CHANNEL, chatId);
            default -> HELP;
        };
        bot.sendMessage(chatId, answer, null);
    }

    // "/start <code>" from a Connect link links the chat; a bare /start explains how.
    private String start(String chatId, String code, String name) {
        if (code.isEmpty()) {
            return messages.isLinked(CHANNEL, chatId) ? HELP : "Hi! " + ChatMessageService.NOT_LINKED;
        }
        return links.link(CHANNEL, chatId, name, code).isPresent() ? LINKED : LINK_FAILED;
    }

    private void voice(String chatId, JsonNode voice) {
        if (!messages.isLinked(CHANNEL, chatId)) {
            bot.sendMessage(chatId, ChatMessageService.NOT_LINKED, null);
            return;
        }
        if (voice.path("duration").asInt() > MAX_VOICE_SECONDS || voice.path("file_size").asLong() > MAX_VOICE_BYTES) {
            bot.sendMessage(chatId, "That voice note is too long. Please keep it under a minute.", null);
            return;
        }
        byte[] audio = bot.downloadFile(voice.path("file_id").asString());
        reply(chatId, messages.handle(CHANNEL, chatId, new ChatInput.Voice(audio, "audio/ogg")), true);
    }

    // The reply as text, with Confirm and Cancel for a proposal. A voice note gets a voice note
    // back too, of the reply without the card.
    private void reply(String chatId, ChatReply reply, boolean spoken) {
        String text = reply.heard() == null ? reply.text() : "🎙 “" + reply.heard() + "”\n\n" + reply.text();
        bot.sendMessage(chatId, text, reply.proposalId());
        if (spoken) {
            messages.speak(CHANNEL, chatId, reply.reply()).ifPresent(audio -> bot.sendVoice(chatId, audio));
        }
    }

    // Confirm or Cancel on a proposal. The buttons are removed first, so a second tap does nothing;
    // a tap that slips through is harmless, since confirming twice saves once.
    private void callback(JsonNode query) {
        bot.answerCallbackQuery(query.path("id").asString());
        JsonNode message = query.path("message");
        String chatId = message.path("chat").path("id").asString();
        Matcher data = CALLBACK.matcher(query.path("data").asString());
        if (chatId.isEmpty() || !data.matches()) {
            return;
        }
        try {
            bot.removeButtons(chatId, message.path("message_id").asLong());
        } catch (TelegramException alreadyRemoved) {
            // Telegram refuses an edit that changes nothing; the buttons are gone either way.
        }
        long proposalId = Long.parseLong(data.group(2));
        ChatReply result = "confirm".equals(data.group(1))
                ? messages.confirm(CHANNEL, chatId, proposalId)
                : messages.cancel(CHANNEL, chatId, proposalId);
        bot.sendMessage(chatId, result.text(), null);
    }

    private static String name(JsonNode user) {
        String name = (user.path("first_name").asString() + " " + user.path("last_name").asString()).strip();
        return name.isEmpty() ? null : name;
    }
}
