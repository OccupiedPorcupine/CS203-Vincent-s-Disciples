package app.chat.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// The Bot API calls, against a stand-in Telegram (issue #100).
class TelegramBotClientTest {

    private static final String TOKEN = "123456:secret-token";
    private static final String OK = "{\"ok\": true, \"result\": true}";

    private MockRestServiceServer server;
    private TelegramBotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TelegramBotClient(builder, "https://api.test", TOKEN, JsonMapper.builder().build());
    }

    @Test
    void aProposalIsSentAsPlainTextWithConfirmAndCancelButtons() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.chat_id").value("42"))
                .andExpect(jsonPath("$.text").value("Save these?\n• Char siew rice-special, Wed 4 Nov: 30"))
                .andExpect(jsonPath("$.parse_mode").doesNotExist())
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].text").value("Confirm"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].callback_data").value("confirm:12"))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][1].callback_data").value("cancel:12"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.sendMessage("42", "Save these?\n• Char siew rice-special, Wed 4 Nov: 30", 12L);
        server.verify();
    }

    @Test
    void aPlainReplyHasNoButtons() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/sendMessage"))
                .andExpect(jsonPath("$.reply_markup").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.sendMessage("42", "Hello", null);
        server.verify();
    }

    @Test
    void aVoiceNoteIsFetchedByItsFilePath() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/getFile"))
                .andExpect(jsonPath("$.file_id").value("voice-1"))
                .andRespond(withSuccess("{\"ok\": true, \"result\": {\"file_path\": \"voice/file_7.oga\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/file/bot" + TOKEN + "/voice/file_7.oga"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new byte[] {79, 103, 103}, MediaType.APPLICATION_OCTET_STREAM));

        assertArrayEquals(new byte[] {79, 103, 103}, client.downloadFile("voice-1"));
        server.verify();
    }

    @Test
    void aReplyVoiceNoteIsUploadedAsMultipart() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/sendVoice"))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("multipart/form-data")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"voice\"; filename=\"reply.ogg\"")))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.sendVoice("42", new byte[] {1, 2, 3});
        server.verify();
    }

    @Test
    void buttonsAreRemovedWithAnEmptyKeyboard() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/editMessageReplyMarkup"))
                .andExpect(jsonPath("$.message_id").value(77))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard").isEmpty())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.removeButtons("42", 77);
        server.verify();
    }

    @Test
    void theWebhookIsSetWithTheSecretAndOnlyTheUpdatesTheBotHandles() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/setWebhook"))
                .andExpect(jsonPath("$.url").value("https://chicky.test/api/channels/telegram/webhook"))
                .andExpect(jsonPath("$.secret_token").value("hook-secret"))
                .andExpect(jsonPath("$.allowed_updates[0]").value("message"))
                .andExpect(jsonPath("$.allowed_updates[1]").value("callback_query"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/setMyCommands"))
                .andExpect(jsonPath("$.commands[0].command").value("today"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.setWebhook("https://chicky.test/api/channels/telegram/webhook", "hook-secret");
        client.setCommands(List.of(new TelegramBotClient.Command("today", "Sales recorded today")));
        server.verify();
    }

    @Test
    void errorsNeverContainTheToken() {
        server.expect(requestTo("https://api.test/bot" + TOKEN + "/sendMessage"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"ok\": false, \"description\": \"Bad Request: chat not found\"}"));

        TelegramException error = assertThrows(TelegramException.class, () -> client.sendMessage("42", "Hello", null));
        assertFalse(error.getMessage().contains(TOKEN), error.getMessage());
        assertTrue(error.getMessage().contains("400"), error.getMessage());
    }
}
