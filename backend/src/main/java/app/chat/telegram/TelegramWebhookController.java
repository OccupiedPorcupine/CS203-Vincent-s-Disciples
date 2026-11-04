package app.chat.telegram;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Where Telegram delivers the bot's updates (issue #100). Public and CSRF-exempt (SecurityConfig):
// a call is trusted only when it carries the secret given to setWebhook.
@Tag(name = "Chat apps")
@RestController
@Conditional(TelegramEnabled.class)
public class TelegramWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhookController.class);

    public static final String PATH = "/api/channels/telegram/webhook";
    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramSettings settings;
    private final TelegramUpdateHandler handler;
    private final JsonMapper jsonMapper;

    public TelegramWebhookController(TelegramSettings settings, TelegramUpdateHandler handler, JsonMapper jsonMapper) {
        this.settings = settings;
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    @Operation(summary = "Telegram webhook", description = "Called by Telegram with each update for the bot. Not for browsers.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Handled, or ignored"),
            @ApiResponse(responseCode = "401", description = "Missing or wrong secret header")
    })
    @PostMapping(PATH)
    public ResponseEntity<Void> webhook(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                        @RequestBody String body) {
        if (!settings.isOurSecret(secret)) {
            log.warn("Telegram webhook call refused: wrong or missing secret");
            return ResponseEntity.status(401).build();
        }
        JsonNode update;
        try {
            update = jsonMapper.readTree(body);
        } catch (JacksonException exception) {
            return ResponseEntity.badRequest().build();
        }
        // Always 200 once the update is ours: Telegram would otherwise resend it again and again.
        try {
            handler.handle(update);
        } catch (RuntimeException exception) {
            log.warn("Telegram update {} failed: {}", update.path("update_id").asString(), exception.toString());
        }
        return ResponseEntity.ok().build();
    }
}
