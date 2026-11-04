package app.chat.telegram;

import app.chat.ChatMessageService;
import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The Telegram bot end to end (issue #100): real webhook calls over HTTP, a real (H2) database
// and the offline assistant, with the Bot API replaced by a mock that records what was sent.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.telegram.bot-token=123456:test-token",
        "app.telegram.bot-username=@ChickyTestBot",
        "app.telegram.webhook-secret=hook-secret"
})
class TelegramWebhookApiTest {

    private static final String PASSWORD = "a-long-unique-password";
    private static final AtomicLong UPDATE_IDS = new AtomicLong(1000);

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @MockitoBean
    TelegramBotClient bot;

    @LocalServerPort
    int port;

    @Autowired JdbcTemplate jdbc;

    private final HttpClient telegram = HttpClient.newHttpClient();
    private Session owner;
    private String chatId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "hawker" + UPDATE_IDS.incrementAndGet() + "@telegram.test";
        owner = signUp(email);
        assertEquals(200, owner.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}").statusCode());
        chatId = String.valueOf(UPDATE_IDS.incrementAndGet());
    }

    @AfterEach
    void deleteTestRows() {
        String owners = "SELECT id FROM app_users WHERE email LIKE '%@telegram.test'";
        String dishes = "SELECT id FROM dishes WHERE owner_id IN (" + owners + ")";
        jdbc.update("DELETE FROM assistant_interactions WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM assistant_daily_usage WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM dish_aliases WHERE dish_id IN (" + dishes + ")");
        jdbc.update("DELETE FROM daily_sales WHERE dish_id IN (" + dishes + ")");
        jdbc.update("DELETE FROM dishes WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM chat_inbound_messages");
        jdbc.update("DELETE FROM app_users WHERE email LIKE '%@telegram.test'");
        reset(bot);
    }

    @Test
    void aCallWithoutTheSecretIsRefused() throws Exception {
        assertEquals(401, post(text("/start whatever"), null).statusCode());
        assertEquals(401, post(text("/start whatever"), "wrong-secret").statusCode());
        verifyNoInteractions(bot);
    }

    @Test
    void theConnectLinkOpensTheBotWithTheCode() throws Exception {
        String code = issueCode();
        assertTrue(owner.get("/api/chat-links").body().contains("\"channel\":\"TELEGRAM\""));

        assertEquals(200, post(text("/start " + code), "hook-secret").statusCode());

        verify(bot).sendMessage(chatId, TelegramUpdateHandler.LINKED, null);
        assertTrue(owner.get("/api/chat-links").body().contains("\"displayName\":\"Ah Hock\""));
    }

    @Test
    void aSaleIsSavedOnlyAfterConfirmAndTheButtonsGoAway() throws Exception {
        link();

        post(text("chicken rice 40 today"), "hook-secret");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> proposal = ArgumentCaptor.forClass(Long.class);
        verify(bot).sendMessage(eq(chatId), sent.capture(), proposal.capture());
        assertTrue(sent.getValue().contains("Save these?\n• Chicken rice, "), sent.getValue());
        assertNotNull(proposal.getValue());
        assertEquals(0, sales());

        post(callback("confirm:" + proposal.getValue(), 555), "hook-secret");

        verify(bot).answerCallbackQuery("callback-" + chatId);
        verify(bot).removeButtons(chatId, 555);
        verify(bot).sendMessage(eq(chatId), startsWith("Saved.\n• Chicken rice"), isNull());
        assertEquals(40, sales());

        post(text("/today"), "hook-secret");
        verify(bot).sendMessage(eq(chatId), contains("• Chicken rice 40"), isNull());
    }

    @Test
    void cancelSavesNothing() throws Exception {
        link();
        post(text("chicken rice 40 today"), "hook-secret");
        ArgumentCaptor<Long> proposal = ArgumentCaptor.forClass(Long.class);
        verify(bot).sendMessage(eq(chatId), anyString(), proposal.capture());

        post(callback("cancel:" + proposal.getValue(), 556), "hook-secret");

        verify(bot).sendMessage(chatId, "Cancelled. Nothing was saved.", null);
        assertEquals(0, sales());
    }

    @Test
    void anUpdateTelegramResendsIsHandledOnce() throws Exception {
        link();
        String update = text("chicken rice 40 today");

        post(update, "hook-secret");
        post(update, "hook-secret");

        verify(bot, times(1)).sendMessage(eq(chatId), contains("Save these?"), notNull());
        assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(*) FROM assistant_interactions WHERE input_mode = 'telegram-text' "
                + "AND owner_id = (SELECT owner_id FROM chat_links WHERE external_id = ?)", Integer.class, chatId));
    }

    @Test
    void anUnlinkedChatIsToldToLinkAndCanUnlinkItself() throws Exception {
        post(text("chicken rice 40 today"), "hook-secret");
        verify(bot).sendMessage(chatId, ChatMessageService.NOT_LINKED, null);

        link();
        post(text("/unlink"), "hook-secret");
        verify(bot).sendMessage(eq(chatId), startsWith("Unlinked."), isNull());
        post(text("/today"), "hook-secret");
        // link() forgot the calls before it, so this is the reply to /today.
        verify(bot).sendMessage(chatId, ChatMessageService.NOT_LINKED, null);
    }

    @Test
    void groupChatsAreIgnored() throws Exception {
        link();
        String group = """
                {"update_id": %d, "message": {"message_id": 1, "chat": {"id": -100, "type": "group"},
                 "from": {"id": 1, "first_name": "Ah Hock"}, "text": "chicken rice 40 today"}}
                """.formatted(UPDATE_IDS.incrementAndGet());

        assertEquals(200, post(group, "hook-secret").statusCode());
        verify(bot, never()).sendMessage(eq("-100"), anyString(), any());
    }

    @Test
    void aLongVoiceNoteIsRefusedWithoutDownloadingIt() throws Exception {
        link();
        String voice = """
                {"update_id": %d, "message": {"message_id": 2, "chat": {"id": %s, "type": "private"},
                 "voice": {"file_id": "voice-1", "duration": 95, "mime_type": "audio/ogg", "file_size": 400000}}}
                """.formatted(UPDATE_IDS.incrementAndGet(), chatId);

        post(voice, "hook-secret");

        verify(bot).sendMessage(chatId, "That voice note is too long. Please keep it under a minute.", null);
        verify(bot, never()).downloadFile(anyString());
    }

    @Test
    void aVoiceNoteIsDownloadedAndTranscribed() throws Exception {
        link();
        when(bot.downloadFile("voice-2")).thenReturn(new byte[] {79, 103, 103});
        String voice = """
                {"update_id": %d, "message": {"message_id": 3, "chat": {"id": %s, "type": "private"},
                 "voice": {"file_id": "voice-2", "duration": 4, "mime_type": "audio/ogg", "file_size": 9000}}}
                """.formatted(UPDATE_IDS.incrementAndGet(), chatId);

        post(voice, "hook-secret");

        // The offline assistant can't transcribe, and says so; with OpenAI the transcript is used.
        verify(bot).downloadFile("voice-2");
        verify(bot).sendMessage(eq(chatId), startsWith("Voice input needs the OpenAI key"), isNull());
    }

    private void link() throws Exception {
        post(text("/start " + issueCode()), "hook-secret");
        clearInvocations(bot);
    }

    private String issueCode() throws Exception {
        HttpResponse<String> response = owner.send("POST", "/api/chat-links/telegram", null);
        assertEquals(200, response.statusCode(), response.body());
        Matcher code = Pattern.compile("\"code\":\"([A-Za-z0-9_-]+)\"").matcher(response.body());
        assertTrue(code.find(), response.body());
        assertTrue(response.body().contains("\"link\":\"https://t.me/ChickyTestBot?start=" + code.group(1) + "\""), response.body());
        return code.group(1);
    }

    private String text(String text) {
        return """
                {"update_id": %d, "message": {"message_id": 1, "chat": {"id": %s, "type": "private"},
                 "from": {"id": %s, "first_name": "Ah", "last_name": "Hock"}, "text": "%s"}}
                """.formatted(UPDATE_IDS.incrementAndGet(), chatId, chatId, text);
    }

    private String callback(String data, long messageId) {
        return """
                {"update_id": %d, "callback_query": {"id": "callback-%s", "data": "%s",
                 "message": {"message_id": %d, "chat": {"id": %s, "type": "private"}}}}
                """.formatted(UPDATE_IDS.incrementAndGet(), chatId, data, messageId, chatId);
    }

    private HttpResponse<String> post(String update, String secret) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + TelegramWebhookController.PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(update));
        if (secret != null) request.header(TelegramWebhookController.SECRET_HEADER, secret);
        return telegram.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int sales() {
        Integer total = jdbc.queryForObject("SELECT COALESCE(SUM(quantity_sold), 0) FROM daily_sales WHERE dish_id IN "
                + "(SELECT id FROM dishes WHERE owner_id = (SELECT owner_id FROM chat_links WHERE external_id = ?))", Integer.class, chatId);
        return total == null ? 0 : total;
    }

    private Session signUp(String email) throws Exception {
        Session session = new Session();
        HttpResponse<String> signup = session.send("POST", "/api/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Stall owner\"}");
        assertEquals(201, signup.statusCode(), signup.body());
        return session;
    }

    private final class Session {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            String csrf = get("/api/csrf").body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf);
            assertTrue(matcher.find(), csrf);
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", matcher.group(1))
                            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
