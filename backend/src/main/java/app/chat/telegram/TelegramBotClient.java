package app.chat.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

// The Bot API methods the bot uses (issue #100), over plain HTTP: a bot framework isn't needed.
// The token is part of every URL (unencoded: Telegram wants its ":" as is), so it is scrubbed from errors.
// Replies are plain text (no parse_mode), so dish names with "." or "-" can't break them.
@Component
@Conditional(TelegramEnabled.class)
public class TelegramBotClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotClient.class);

    // Telegram refuses longer messages.
    static final int MAX_TEXT = 4096;

    private final RestClient restClient;
    private final String apiBaseUrl;
    private final String token;
    private final JsonMapper jsonMapper;

    // Marked for Spring, which can't choose between two constructors and would otherwise look for
    // a no-argument one: with TELEGRAM_BOT_TOKEN set, the backend failed to start.
    @Autowired
    public TelegramBotClient(TelegramSettings settings, JsonMapper jsonMapper) {
        this(RestClient.builder().requestFactory(requestFactory()), settings.apiBaseUrl(), settings.token(), jsonMapper);
    }

    TelegramBotClient(RestClient.Builder builder, String apiBaseUrl, String token, JsonMapper jsonMapper) {
        this.restClient = builder.build();
        this.apiBaseUrl = apiBaseUrl;
        this.token = token;
        this.jsonMapper = jsonMapper;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    // A command in the bot's menu, e.g. ("today", "Sales recorded today").
    public record Command(String command, String description) {}

    // Telegram sends updates to url, with secret in the X-Telegram-Bot-Api-Secret-Token header.
    public void setWebhook(String url, String secret) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("url", url);
        request.put("secret_token", secret);
        request.putArray("allowed_updates").add("message").add("callback_query");
        call("setWebhook", request);
    }

    public void setCommands(List<Command> commands) {
        ObjectNode request = jsonMapper.createObjectNode();
        ArrayNode list = request.putArray("commands");
        commands.forEach(command -> list.addObject().put("command", command.command()).put("description", command.description()));
        call("setMyCommands", request);
    }

    // Sends text, with Confirm and Cancel buttons for the proposal when proposalId is set.
    public void sendMessage(String chatId, String text, Long proposalId) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("chat_id", chatId);
        request.put("text", text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…");
        if (proposalId != null) {
            ArrayNode row = request.putObject("reply_markup").putArray("inline_keyboard").addArray();
            row.addObject().put("text", "Confirm").put("callback_data", TelegramUpdateHandler.CONFIRM + proposalId);
            row.addObject().put("text", "Cancel").put("callback_data", TelegramUpdateHandler.CANCEL + proposalId);
        }
        call("sendMessage", request);
    }

    // A voice note: Ogg Opus audio, shown with a play button and waveform.
    public void sendVoice(String chatId, byte[] oggOpus) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", chatId);
        form.add("voice", new ByteArrayResource(oggOpus) {
            @Override
            public String getFilename() {
                return "reply.ogg";
            }
        });
        request("sendVoice", () -> restClient.post()
                .uri(URI.create(apiBaseUrl + "/bot" + token + "/sendVoice"))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(String.class));
    }

    // The bytes of a file a user sent, such as a voice note.
    public byte[] downloadFile(String fileId) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("file_id", fileId);
        String path = call("getFile", request).path("file_path").asString();
        if (path.isEmpty()) {
            throw new TelegramException("getFile returned no file_path");
        }
        return request("file download", () -> restClient.get()
                .uri(URI.create(apiBaseUrl + "/file/bot" + token + "/" + path))
                .retrieve()
                .body(byte[].class));
    }

    // Stops the spinner on the tapped button.
    public void answerCallbackQuery(String callbackQueryId) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("callback_query_id", callbackQueryId);
        call("answerCallbackQuery", request);
    }

    // Removes a message's buttons, so a proposal can't be tapped again.
    public void removeButtons(String chatId, long messageId) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("chat_id", chatId);
        request.put("message_id", messageId);
        request.putObject("reply_markup").putArray("inline_keyboard");
        call("editMessageReplyMarkup", request);
    }

    // POSTs a JSON request to a Bot API method and returns its result.
    private JsonNode call(String method, ObjectNode request) {
        String body = request(method, () -> restClient.post()
                .uri(URI.create(apiBaseUrl + "/bot" + token + "/" + method))
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(request))
                .retrieve()
                .body(String.class));
        return jsonMapper.readTree(body).path("result");
    }

    private <T> T request(String what, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            // Telegram's error body says why, e.g. "Bad Request: chat not found". It never holds the token.
            String reason = scrub(exception.getResponseBodyAsString());
            log.warn("Telegram {} failed with {}: {}", what, exception.getStatusCode().value(), reason);
            throw new TelegramException("Telegram " + what + " failed with " + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            log.warn("Telegram {} failed: {}", what, scrub(String.valueOf(exception.getMessage())));
            throw new TelegramException("Telegram " + what + " failed");
        }
    }

    // Connection errors quote the URL, which holds the token.
    private String scrub(String text) {
        return token.isEmpty() ? text : text.replace(token, "<token>");
    }
}
