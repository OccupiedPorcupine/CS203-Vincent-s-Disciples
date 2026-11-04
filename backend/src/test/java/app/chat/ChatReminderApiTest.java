package app.chat;

import app.chat.telegram.TelegramBotClient;
import app.closure.ClosedDateRepository;
import app.closure.RestDayRepository;
import app.dish.Dish;
import app.dish.DishRepository;
import app.sale.DailySaleRepository;
import app.service.DailySaleService;
import app.service.DishService;
import app.service.PublicHolidaySyncService;
import app.service.StallClosureService;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The evening sales reminder and prep list (issue #101) on H2, at 21:10 on Wednesday 4 November
// 2026 in Singapore, with the Bot API replaced by a mock that records what was sent.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.telegram.bot-token=123456:test-token",
        "app.telegram.bot-username=ChickyTestBot",
        "app.telegram.webhook-secret=hook-secret",
        "app.chat.cron-secret=cron-secret"
})
class ChatReminderApiTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    static final LocalTime NINE_PM = LocalTime.of(21, 0);
    private static final String PASSWORD = "a-long-unique-password";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-04T13:10:00Z"), ZoneId.of("Asia/Singapore"));
        }
    }

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @MockitoBean
    TelegramBotClient bot;

    @LocalServerPort
    int port;

    @Autowired ChatReminderService reminders;
    @Autowired ChatCommands commands;
    @Autowired ChatLinkRepository links;
    @Autowired AppUserRepository users;
    @Autowired DishService dishService;
    @Autowired DishRepository dishRepository;
    @Autowired DailySaleService saleService;
    @Autowired DailySaleRepository saleRepository;
    @Autowired StallClosureService closureService;
    @Autowired ClosedDateRepository closedDateRepository;
    @Autowired RestDayRepository restDayRepository;

    Long owner;
    Dish chickenRice;
    String chatId;

    @BeforeEach
    void setUp() {
        owner = users.save(AppUser.localUser(UUID.randomUUID() + "@reminders.test", "{pbkdf2}hash", "Hawker")).getId();
        chickenRice = dishService.createDish("Chicken rice", owner);
        chatId = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
    }

    // Other tests share this database and delete users, so remove everything that points at them.
    @AfterEach
    void cleanUp() {
        links.deleteAll();
        closedDateRepository.deleteAll();
        restDayRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
        reset(bot);
    }

    private ChatLink link(Long ownerId, String chat, LocalTime salesReminderAt, LocalTime prepListAt) {
        ChatLink link = new ChatLink(users.findById(ownerId).orElseThrow(), ChatChannel.TELEGRAM, chat, "Ah Hock", Instant.now());
        link.setReminders(salesReminderAt, prepListAt);
        return links.save(link);
    }

    // Four weeks of sales up to yesterday, so tomorrow has a forecast.
    private void history() {
        for (LocalDate day = TODAY.minusDays(28); day.isBefore(TODAY); day = day.plusDays(1)) {
            saleService.setSale(chickenRice.getId(), owner, day, 40);
        }
    }

    @Test
    void aStallWithNoSalesTodayGetsOneReminderEvenIfTheJobRunsTwice() {
        link(owner, chatId, NINE_PM, null);

        assertEquals(new ChatReminderService.Run(1, 0, 0), reminders.run());
        assertEquals(new ChatReminderService.Run(0, 0, 0), reminders.run());

        verify(bot, times(1)).sendMessage(chatId,
                "You haven't sent today's sales (Wed 4 Nov) yet. Reply with them, like \"chicken rice 40, char siew 30\".", null);
    }

    @Test
    void noReminderOnceTheSalesAreInOrOnARestDay() {
        link(owner, chatId, NINE_PM, null);
        saleService.setSale(chickenRice.getId(), owner, TODAY, 35);
        Long resting = users.save(AppUser.localUser(UUID.randomUUID() + "@reminders.test", "{pbkdf2}hash", "Resting")).getId();
        closureService.setRestDays(resting, List.of(DayOfWeek.WEDNESDAY));
        link(resting, chatId + "1", NINE_PM, null);

        assertEquals(new ChatReminderService.Run(0, 2, 0), reminders.run());
        verifyNoInteractions(bot);
    }

    @Test
    void nothingIsSentBeforeItsTime() {
        link(owner, chatId, LocalTime.of(22, 0), LocalTime.of(22, 0));

        assertEquals(new ChatReminderService.Run(0, 0, 0), reminders.run());
        verifyNoInteractions(bot);
    }

    @Test
    void thePrepListIsTheSameAsTheCommand() {
        history();
        link(owner, chatId, null, LocalTime.of(20, 0));
        String expected = commands.prep(ChatChannel.TELEGRAM, chatId);
        assertTrue(expected.startsWith("Prep for Thu 5 Nov:\n• Chicken rice "), expected);

        assertEquals(new ChatReminderService.Run(1, 0, 0), reminders.run());

        verify(bot).sendMessage(chatId, expected, null);
    }

    @Test
    void aClosedDayTomorrowIsANoteButARestDayIsNothing() {
        history();
        link(owner, chatId, null, NINE_PM);
        closureService.closeDate(owner, TODAY.plusDays(1), "Centre cleaning");

        reminders.run();
        verify(bot).sendMessage(eq(chatId), startsWith("Nothing to prepare for tomorrow. You marked Thursday 5 Nov as closed (Centre cleaning)."), isNull());

        reset(bot);
        Long resting = users.save(AppUser.localUser(UUID.randomUUID() + "@reminders.test", "{pbkdf2}hash", "Resting")).getId();
        closureService.setRestDays(resting, List.of(DayOfWeek.THURSDAY));
        link(resting, chatId + "1", null, NINE_PM);
        assertEquals(new ChatReminderService.Run(0, 1, 0), reminders.run());
        verifyNoInteractions(bot);
    }

    @Test
    void aChatTheBotCantReachDoesNotStopTheOthers() {
        link(owner, chatId, NINE_PM, null);
        Long other = users.save(AppUser.localUser(UUID.randomUUID() + "@reminders.test", "{pbkdf2}hash", "Other")).getId();
        link(other, chatId + "1", NINE_PM, null);
        doThrow(new RuntimeException("Forbidden: bot was blocked by the user")).when(bot).sendMessage(eq(chatId), anyString(), isNull());

        assertEquals(new ChatReminderService.Run(1, 0, 1), reminders.run());
        verify(bot).sendMessage(eq(chatId + "1"), startsWith("You haven't sent today's sales"), isNull());
    }

    @Test
    void theJobNeedsTheCronSecret() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/channels/jobs/run"))
                .POST(HttpRequest.BodyPublishers.noBody());

        assertEquals(401, http.send(request.copy().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, http.send(request.copy().header("Authorization", "Bearer wrong").build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        link(owner, chatId, NINE_PM, null);
        HttpResponse<String> run = http.send(request.copy().header("Authorization", "Bearer cron-secret").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, run.statusCode());
        assertEquals("{\"sent\":1,\"skipped\":0,\"failed\":0}", run.body());
    }

    @Test
    void theOwnerSetsTheTimesOnTheAccountPage() throws Exception {
        Session session = new Session();
        String email = UUID.randomUUID() + "@reminders.test";
        assertEquals(201, session.send("POST", "/api/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"name\":\"Hawker\"}").statusCode());
        Long id = link(users.findByEmailIgnoreCase(email).orElseThrow().getId(), chatId, null, null).getId();

        HttpResponse<String> saved = session.send("PUT", "/api/chat-links/" + id + "/reminders",
                "{\"salesReminderAt\":\"21:00\",\"prepListAt\":null}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"salesReminderAt\":\"21:00\",\"prepListAt\":null"), saved.body());

        HttpResponse<String> notOnTheHour = session.send("PUT", "/api/chat-links/" + id + "/reminders", "{\"salesReminderAt\":\"21:30\"}");
        assertEquals(400, notOnTheHour.statusCode());
        assertTrue(notOnTheHour.body().contains("\"code\":\"INVALID_REMINDER_TIME\""), notOnTheHour.body());
        assertEquals(400, session.send("PUT", "/api/chat-links/" + id + "/reminders", "{\"prepListAt\":\"09:00\"}").statusCode());

        Long someoneElses = link(owner, chatId + "1", null, null).getId();
        assertEquals(404, session.send("PUT", "/api/chat-links/" + someoneElses + "/reminders", "{\"salesReminderAt\":\"21:00\"}").statusCode());
        assertNull(links.findById(someoneElses).orElseThrow().getSalesReminderAt());
    }

    @Test
    void remindCommandChangesTheSettingsFromTheChat() {
        link(owner, chatId, null, null);

        assertTrue(commands.remind(ChatChannel.TELEGRAM, chatId, "").startsWith("Sales reminder: off. Prep list for tomorrow: off."));
        assertTrue(commands.remind(ChatChannel.TELEGRAM, chatId, "sales 9pm").startsWith("Sales reminder: 9 pm. Prep list for tomorrow: off."));
        assertTrue(commands.remind(ChatChannel.TELEGRAM, chatId, "prep on").startsWith("Sales reminder: 9 pm. Prep list for tomorrow: 9 pm."));
        assertTrue(commands.remind(ChatChannel.TELEGRAM, chatId, "PREP 8 pm").startsWith("Sales reminder: 9 pm. Prep list for tomorrow: 8 pm."));
        assertEquals(ChatLinkService.REMINDER_TIMES + " For example, /remind sales 9pm.", commands.remind(ChatChannel.TELEGRAM, chatId, "sales 7am"));
        assertEquals(ChatCommands.REMIND_HELP, commands.remind(ChatChannel.TELEGRAM, chatId, "tomorrow"));

        ChatLink link = links.findByChannelAndExternalId(ChatChannel.TELEGRAM, chatId).orElseThrow();
        assertEquals(NINE_PM, link.getSalesReminderAt());
        assertEquals(LocalTime.of(20, 0), link.getPrepListAt());

        assertTrue(commands.remind(ChatChannel.TELEGRAM, chatId, "off").startsWith("Reminders off."));
        assertNull(links.findByChannelAndExternalId(ChatChannel.TELEGRAM, chatId).orElseThrow().getPrepListAt());
        assertEquals(ChatMessageService.NOT_LINKED, commands.remind(ChatChannel.TELEGRAM, "999", "sales 9pm"));
    }

    @Test
    void reminderTimesAreReadAsTheEvening() {
        assertEquals(NINE_PM, ChatCommands.reminderTime("9pm"));
        assertEquals(NINE_PM, ChatCommands.reminderTime("9:00 pm"));
        assertEquals(NINE_PM, ChatCommands.reminderTime("21:00"));
        assertEquals(NINE_PM, ChatCommands.reminderTime("9"));
        assertEquals(LocalTime.NOON, ChatCommands.reminderTime("12pm"));
        assertEquals(LocalTime.MIDNIGHT, ChatCommands.reminderTime("12am"));
        assertNull(ChatCommands.reminderTime("13pm"));
        assertNull(ChatCommands.reminderTime("soon"));
        assertEquals("9 pm", ChatCommands.clockTime(NINE_PM));
        assertEquals("12 pm", ChatCommands.clockTime(LocalTime.NOON));
        assertEquals("off", ChatCommands.clockTime(null));
    }

    private final class Session {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> send(String method, String path, String body) throws Exception {
            String csrf = http.send(HttpRequest.newBuilder(uri("/api/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf);
            assertTrue(matcher.find(), csrf);
            return http.send(HttpRequest.newBuilder(uri(path))
                            .header("Content-Type", "application/json")
                            .header("X-XSRF-TOKEN", matcher.group(1))
                            .method(method, HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
