package app.chat;

import app.assistant.AssistantModelClient.ToolSpec;
import app.assistant.AssistantTools;
import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// Linking a chat to an account and recording sales from it (issue #99), over real HTTP and a real
// (H2) database, with a stand-in Telegram connector and the offline assistant.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatLinkApiTest {

    private static final String PASSWORD = "a-long-unique-password";
    private static final ChatChannel CHANNEL = ChatChannel.TELEGRAM;

    @TestConfiguration
    static class Config {
        @Bean
        ChatConnector fakeTelegram() {
            return new ChatConnector() {
                @Override public ChatChannel channel() { return CHANNEL; }
                @Override public String linkFor(String code) { return "https://t.me/TestBot?start=" + code; }
            };
        }
    }

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired ChatLinkService chatLinks;
    @Autowired ChatMessageService chatMessages;
    @Autowired AssistantTools tools;

    @AfterEach
    void deleteTestRows() {
        String owners = "SELECT id FROM app_users WHERE email LIKE '%@chat.test'";
        String dishes = "SELECT id FROM dishes WHERE owner_id IN (" + owners + ")";
        jdbc.update("DELETE FROM assistant_interactions WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM assistant_daily_usage WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM dish_aliases WHERE dish_id IN (" + dishes + ")");
        jdbc.update("DELETE FROM daily_sales WHERE dish_id IN (" + dishes + ")");
        jdbc.update("DELETE FROM dishes WHERE owner_id IN (" + owners + ")");
        jdbc.update("DELETE FROM chat_inbound_messages");
        jdbc.update("DELETE FROM app_users WHERE email LIKE '%@chat.test'");
    }

    @Test
    void aLinkedChatRecordsSalesOnlyWhenTheOwnerConfirms() throws Exception {
        Session owner = signUp("hawker@chat.test");
        assertEquals(200, owner.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}").statusCode());
        assertEquals(ChatMessageService.NOT_LINKED, chatMessages.handle(CHANNEL, "chat-1", text("chicken rice 40 today")).text());

        String code = issueCode(owner);
        assertTrue(chatLinks.link(CHANNEL, "chat-1", "Ah Hock", code).isPresent());
        String listed = owner.get("/api/chat-links").body();
        assertTrue(listed.contains("\"channel\":\"TELEGRAM\"") && listed.contains("\"displayName\":\"Ah Hock\""), listed);

        ChatReply proposal = chatMessages.handle(CHANNEL, "chat-1", text("chicken rice 40 today"));
        assertNotNull(proposal.proposalId(), proposal.text());
        assertTrue(proposal.text().contains("Save these?\n• Chicken rice, "), proposal.text());
        assertTrue(proposal.text().endsWith(": 40"), proposal.text());
        assertEquals(0, salesOf("hawker@chat.test"), "nothing is saved before Confirm");

        assertTrue(chatMessages.confirm(CHANNEL, "chat-1", proposal.proposalId()).text().startsWith("Saved.\n• Chicken rice"));
        assertEquals(40, salesOf("hawker@chat.test"));

        // A second tap on Confirm saves nothing more.
        assertTrue(chatMessages.confirm(CHANNEL, "chat-1", proposal.proposalId()).text().startsWith("Saved."));
        assertEquals(40, salesOf("hawker@chat.test"));

        String mode = jdbc.queryForObject("SELECT input_mode FROM assistant_interactions WHERE id = ?", String.class, proposal.proposalId());
        assertEquals("telegram-text", mode);
    }

    @Test
    void cancellingSavesNothingAndConfirmingAfterwardsIsRefused() throws Exception {
        Session owner = signUp("cancel@chat.test");
        owner.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}");
        chatLinks.link(CHANNEL, "chat-2", null, issueCode(owner));

        ChatReply proposal = chatMessages.handle(CHANNEL, "chat-2", text("chicken rice 40 today"));
        assertEquals("Cancelled. Nothing was saved.", chatMessages.cancel(CHANNEL, "chat-2", proposal.proposalId()).text());
        assertEquals("This proposal was cancelled.", chatMessages.confirm(CHANNEL, "chat-2", proposal.proposalId()).text());
        assertEquals(0, salesOf("cancel@chat.test"));
    }

    @Test
    void aCodeIsStoredHashedAndWorksOnceBeforeItExpires() throws Exception {
        Session owner = signUp("codes@chat.test");
        String code = issueCode(owner);

        String stored = jdbc.queryForObject("SELECT token_hash FROM account_tokens WHERE purpose = 'CHAT_LINK' AND user_id = "
                + "(SELECT id FROM app_users WHERE email = 'codes@chat.test')", String.class);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))), stored);
        assertFalse(stored.contains(code));

        assertTrue(chatLinks.link(CHANNEL, "chat-3", null, code).isPresent());
        assertTrue(chatLinks.link(CHANNEL, "chat-4", null, code).isEmpty(), "a used code is refused");
        assertFalse(chatMessages.isLinked(CHANNEL, "chat-4"));

        String expiring = issueCode(owner);
        jdbc.update("UPDATE account_tokens SET expires_at = DATEADD('MINUTE', -1, CURRENT_TIMESTAMP) WHERE purpose = 'CHAT_LINK'");
        assertTrue(chatLinks.link(CHANNEL, "chat-4", null, expiring).isEmpty(), "an expired code is refused");
        assertTrue(chatLinks.link(CHANNEL, "chat-4", null, "made-up-code").isEmpty());
    }

    @Test
    void anUnlinkedChatCanNoLongerReadOrWrite() throws Exception {
        Session owner = signUp("unlink@chat.test");
        owner.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}");
        Long linkId = chatLinks.link(CHANNEL, "chat-5", null, issueCode(owner)).orElseThrow().getId();
        ChatReply proposal = chatMessages.handle(CHANNEL, "chat-5", text("chicken rice 40 today"));

        assertEquals(204, owner.send("DELETE", "/api/chat-links/" + linkId, null).statusCode());

        assertEquals(ChatMessageService.NOT_LINKED, chatMessages.handle(CHANNEL, "chat-5", text("how many chicken rice yesterday?")).text());
        assertEquals(ChatMessageService.NOT_LINKED, chatMessages.confirm(CHANNEL, "chat-5", proposal.proposalId()).text());
        assertEquals(0, salesOf("unlink@chat.test"));
        assertTrue(owner.get("/api/chat-links").body().contains("\"linked\":null"));
    }

    @Test
    void aChatCanOnlyConfirmItsOwnAccountsProposals() throws Exception {
        Session first = signUp("first@chat.test");
        first.send("POST", "/api/dishes", "{\"name\":\"Chicken rice\"}");
        chatLinks.link(CHANNEL, "chat-6", null, issueCode(first));
        Session second = signUp("second@chat.test");
        chatLinks.link(CHANNEL, "chat-7", null, issueCode(second));

        ChatReply proposal = chatMessages.handle(CHANNEL, "chat-6", text("chicken rice 40 today"));

        assertEquals("Proposal not found.", chatMessages.confirm(CHANNEL, "chat-7", proposal.proposalId()).text());
        assertEquals(0, salesOf("first@chat.test"));
        // Someone else's account can't unlink it either.
        Long linkId = jdbc.queryForObject("SELECT id FROM chat_links WHERE external_id = 'chat-6'", Long.class);
        assertEquals(404, second.send("DELETE", "/api/chat-links/" + linkId, null).statusCode());
    }

    @Test
    void linkingAgainMovesTheChatAndReplacesTheAccountsEarlierChat() throws Exception {
        Session first = signUp("move1@chat.test");
        Session second = signUp("move2@chat.test");
        chatLinks.link(CHANNEL, "chat-8", null, issueCode(first));
        chatLinks.link(CHANNEL, "chat-9", null, issueCode(first));
        assertFalse(chatMessages.isLinked(CHANNEL, "chat-8"), "one chat per account per channel");

        chatLinks.link(CHANNEL, "chat-9", null, issueCode(second));
        assertTrue(first.get("/api/chat-links").body().contains("\"linked\":null"));
        assertFalse(second.get("/api/chat-links").body().contains("\"linked\":null"));
    }

    @Test
    void onlyChannelsSetUpOnTheServerCanBeLinked() throws Exception {
        Session owner = signUp("channels@chat.test");

        String listed = owner.get("/api/chat-links").body();
        assertTrue(listed.contains("TELEGRAM") && !listed.contains("WHATSAPP"), listed);
        HttpResponse<String> whatsapp = owner.send("POST", "/api/chat-links/whatsapp", null);
        assertEquals(404, whatsapp.statusCode());
        assertTrue(whatsapp.body().contains("CHANNEL_NOT_CONFIGURED"), whatsapp.body());
        assertEquals(404, owner.send("POST", "/api/chat-links/carrier-pigeon", null).statusCode());
        assertEquals(401, new Session().get("/api/chat-links").statusCode());
    }

    @Test
    void withoutATelegramTokenTheWebhookDoesNotExist() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/channels/telegram/webhook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"update_id\": 1}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test
    void aResentMessageIsHandledOnce() {
        assertTrue(chatMessages.firstDelivery(CHANNEL, "update-1"));
        assertFalse(chatMessages.firstDelivery(CHANNEL, "update-1"));
        assertTrue(chatMessages.firstDelivery(ChatChannel.WHATSAPP, "update-1"), "IDs are per channel");
    }

    @Test
    void howToQuestionsInAChatGetALinkInsteadOfATour() throws Exception {
        Session owner = signUp("guide@chat.test");
        chatLinks.link(CHANNEL, "chat-10", null, issueCode(owner));

        ChatReply reply = chatMessages.handle(CHANNEL, "chat-10", text("how do I upload training data?"));

        assertTrue(reply.text().contains("http://localhost:5500/data.html"), reply.text());
        assertNull(reply.proposalId());
    }

    @Test
    void onlyTheWebGetsGuidedTours() {
        assertTrue(tools.specs("dashboard").stream().map(ToolSpec::name).toList().contains("guide_user"));
        assertFalse(tools.specs("chat").stream().map(ToolSpec::name).toList().contains("guide_user"));
    }

    @Test
    void aChatKeepsItsConversationUntilItHasBeenQuietForSixHours() {
        ChatLink link = new ChatLink(null, CHANNEL, "chat", null, Instant.EPOCH);
        Instant start = Instant.parse("2026-11-04T02:00:00Z");

        String first = link.conversationFor(start, ChatMessageService.CONVERSATION_IDLE);
        assertEquals(first, link.conversationFor(start.plus(Duration.ofHours(5)), ChatMessageService.CONVERSATION_IDLE));
        assertNotEquals(first, link.conversationFor(start.plus(Duration.ofHours(12)), ChatMessageService.CONVERSATION_IDLE));
    }

    private static ChatInput text(String text) {
        return new ChatInput.Text(text);
    }

    private String issueCode(Session session) throws Exception {
        HttpResponse<String> response = session.send("POST", "/api/chat-links/telegram", null);
        assertEquals(200, response.statusCode(), response.body());
        Matcher code = Pattern.compile("\"code\":\"([A-Za-z0-9_-]{43})\"").matcher(response.body());
        assertTrue(code.find(), response.body());
        assertTrue(response.body().contains("\"link\":\"https://t.me/TestBot?start=" + code.group(1) + "\""), response.body());
        return code.group(1);
    }

    private int salesOf(String email) {
        List<Integer> quantities = jdbc.queryForList("SELECT quantity_sold FROM daily_sales WHERE dish_id IN "
                + "(SELECT id FROM dishes WHERE owner_id = (SELECT id FROM app_users WHERE email = ?))", Integer.class, email);
        return quantities.stream().mapToInt(Integer::intValue).sum();
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
