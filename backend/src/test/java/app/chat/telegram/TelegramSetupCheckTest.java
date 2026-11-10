package app.chat.telegram;

import app.diagnostics.SetupCheck.Result;
import app.diagnostics.SetupCheck.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// The Telegram setup check against a stand-in Telegram: which bot the token is for, and whether
// its webhook points at this host.
class TelegramSetupCheckTest {

    private static final String TOKEN = "123456:secret-token";
    private static final String HERE = "https://chicky-app.test";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer telegram = MockRestServiceServer.bindTo(builder).build();

    @Test
    void withoutATokenTheBotIsOff() {
        assertEquals(Status.OFF, check("", "chicky_bot").check().status());
    }

    @Test
    void aBotWhoseWebhookPointsHereIsOk() {
        answers("chicky_bot", HERE + TelegramWebhookController.PATH);

        Result result = check(TOKEN, "@Chicky_Bot").check();

        assertEquals(Status.OK, result.status(), result.detail());
        telegram.verify();
    }

    @Test
    void aWebhookOnTheOtherHostIsAProblemThatNamesIt() {
        answers("chicky_bot", "https://chicky-zeta.test" + TelegramWebhookController.PATH);

        Result result = check(TOKEN, "chicky_bot").check();

        assertEquals(Status.PROBLEM, result.status());
        assertTrue(result.detail().contains("https://chicky-zeta.test"), result.detail());
    }

    @Test
    void aTokenForAnotherBotIsAProblem() {
        answers("someone_elses_bot", HERE + TelegramWebhookController.PATH);

        Result result = check(TOKEN, "chicky_bot").check();

        assertEquals(Status.PROBLEM, result.status());
        assertTrue(result.detail().contains("@someone_elses_bot"), result.detail());
    }

    @Test
    void aRefusedTokenIsAProblemWithoutTheToken() {
        telegram.expect(requestTo("https://api.test/bot" + TOKEN + "/getMe"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"ok\":false,\"description\":\"Unauthorized\"}"));

        Result result = check(TOKEN, "chicky_bot").check();

        assertEquals(Status.PROBLEM, result.status());
        assertFalse(result.detail().contains(TOKEN), result.detail());
    }

    private void answers(String username, String webhook) {
        telegram.expect(requestTo("https://api.test/bot" + TOKEN + "/getMe"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"username\":\"" + username + "\"}}", MediaType.APPLICATION_JSON));
        telegram.expect(requestTo("https://api.test/bot" + TOKEN + "/getWebhookInfo"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"url\":\"" + webhook + "\"}}", MediaType.APPLICATION_JSON));
    }

    private TelegramSetupCheck check(String token, String username) {
        TelegramSettings settings = new TelegramSettings(token, username, "webhook-secret", "https://api.test", HERE);
        TelegramBotClient client = new TelegramBotClient(builder, "https://api.test", token, JsonMapper.builder().build());
        var bot = new StaticListableBeanFactory(Map.of("bot", client)).getBeanProvider(TelegramBotClient.class);
        return new TelegramSetupCheck(settings, bot);
    }
}
