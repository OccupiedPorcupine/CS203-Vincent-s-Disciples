package app.chat.telegram;

import app.chat.ChatChannel;
import app.chat.ChatLinkService;
import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The backend starts with the bot turned on and its real Bot API client. The other Telegram tests
// replace the client with a mock, which hid that Spring couldn't create it.
@SpringBootTest(properties = {
        "app.telegram.bot-token=123456:test-token",
        "app.telegram.bot-username=ChickyTestBot",
        "app.telegram.webhook-secret=hook-secret"
})
class TelegramStartupTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired TelegramBotClient bot;
    @Autowired ChatLinkService links;

    @Test
    void theBotIsOnWithItsRealClient() {
        assertNotNull(bot);
        assertTrue(links.isConfigured(ChatChannel.TELEGRAM));
    }
}
