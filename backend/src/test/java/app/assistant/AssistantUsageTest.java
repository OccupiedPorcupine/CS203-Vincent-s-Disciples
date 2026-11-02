package app.assistant;

import app.dto.AssistantMessageRequest;
import app.dto.AssistantMessageResponse;
import app.service.PublicHolidaySyncService;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Daily allowances and usage logging (issue #65), with a stand-in for OpenAI that reports token
// usage and can fail on demand. The test properties allow 10 messages and 2 spoken replies a day
// on the FREE plan, below AssistantRateLimiter's 20 a minute so that doesn't step in first.
@SpringBootTest
class AssistantUsageTest {

    static final int FREE_MESSAGES = 10;
    static final int FREE_SPOKEN_REPLIES = 2;

    static class StubModelClient implements AssistantModelClient {
        static final Usage TOKENS = new Usage(1200, 1000, 80);
        private final JsonMapper jsonMapper = JsonMapper.builder().build();
        boolean failing;

        @Override public String modelName() { return "stub"; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean supportsVoice() { return true; }

        @Override
        public Transcription transcribe(byte[] audio, String filename, List<String> keywords) {
            failIfAsked();
            return new Transcription("chicken rice 42", List.of("en"), 4.2);
        }

        @Override
        public byte[] speak(String text) {
            failIfAsked();
            return new byte[] {1, 2, 3};
        }

        @Override
        public ModelResponse respond(String instructions, List<JsonNode> input, List<ToolSpec> tools) {
            failIfAsked();
            JsonNode message = jsonMapper.readTree(
                    "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello!\"}]}");
            return new ModelResponse(List.of(message), List.of(), "Hello!", TOKENS);
        }

        private void failIfAsked() {
            if (failing) {
                throw AssistantException.upstream("The AI service could not be reached. Please try again.");
            }
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        StubModelClient stubModelClient() {
            return new StubModelClient();
        }

        @Bean
        @Primary
        AssistantServiceTest.MutableClock usageTestClock() {
            return new AssistantServiceTest.MutableClock();
        }
    }

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired AssistantService assistant;
    @Autowired AssistantUsageService usageService;
    @Autowired AssistantDailyUsageRepository usageRepository;
    @Autowired AssistantInteractionRepository interactionRepository;
    @Autowired AppUserRepository users;
    @Autowired StubModelClient model;
    @Autowired AssistantServiceTest.MutableClock clock;

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    Long owner;

    @BeforeEach
    void setUp() {
        clock.now = Instant.parse("2026-10-29T04:00:00Z"); // noon in Singapore
        model.failing = false;
        owner = users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
    }

    // Other tests delete every user, which these rows would block.
    @AfterEach
    void cleanUp() {
        interactionRepository.deleteAll();
        usageRepository.deleteAll();
    }

    private AssistantMessageResponse say(String text) {
        return assistant.handleMessage(owner, new AssistantMessageRequest(null, text, "dashboard", "text", null));
    }

    private AssistantDailyUsage todaysRow() {
        return usageRepository.findByOwnerIdAndUsageDate(owner, TODAY).orElseThrow();
    }

    private static MockMultipartFile recording() {
        return new MockMultipartFile("audio", "speech.webm", "audio/webm", new byte[] {1, 2, 3});
    }

    @Test
    void messagesStopAtTheDailyAllowanceWithAFriendlyMessage() {
        for (int message = 1; message <= FREE_MESSAGES; message++) {
            assertEquals(message, say("hello " + message).usage().messagesUsed());
        }

        AssistantException error = assertThrows(AssistantException.class, () -> say("one more"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals("DAILY_LIMIT", error.getCode());
        assertTrue(error.getMessage().contains("all 10 assistant messages"), error.getMessage());
        assertTrue(error.getMessage().contains("今天的 10 条助手消息已用完"), error.getMessage());
        assertEquals(FREE_MESSAGES, todaysRow().getMessages());
        assertEquals(FREE_MESSAGES, interactionRepository.count());
    }

    @Test
    void theAllowanceStartsAgainAtMidnightInSingapore() {
        for (int message = 0; message < FREE_MESSAGES; message++) {
            say("hello");
        }
        clock.now = Instant.parse("2026-10-29T15:59:00Z"); // 23:59 in Singapore
        assertEquals("DAILY_LIMIT", assertThrows(AssistantException.class, () -> say("late")).getCode());

        clock.now = clock.now.plus(Duration.ofMinutes(1));

        assertEquals(1, say("good morning").usage().messagesUsed());
    }

    @Test
    void aMessageThatFailsDoesNotUseTheAllowance() {
        say("hello");
        model.failing = true;

        assertEquals("ASSISTANT_UPSTREAM_ERROR", assertThrows(AssistantException.class, () -> say("hello?")).getCode());

        assertEquals(1, usageService.today(owner).messagesUsed());
    }

    @Test
    void tokensAreLoggedForEachMessageAndAddedUpForTheDay() {
        say("hello");
        say("hello again");

        var interaction = interactionRepository.findAll().getFirst();
        assertEquals(1200, interaction.getInputTokens());
        assertEquals(1000, interaction.getCachedTokens());
        assertEquals(80, interaction.getOutputTokens());

        AssistantDailyUsage day = todaysRow();
        assertEquals(2400, day.getInputTokens());
        assertEquals(2000, day.getCachedTokens());
        assertEquals(160, day.getOutputTokens());
    }

    @Test
    void spokenRepliesHaveTheirOwnAllowanceAndTextCarriesOn() {
        String first = "First reply " + UUID.randomUUID();
        String second = "Second reply " + UUID.randomUUID();
        assistant.speak(owner, first);
        assistant.speak(owner, second);

        AssistantException error = assertThrows(AssistantException.class,
                () -> assistant.speak(owner, "Third reply " + UUID.randomUUID()));

        assertEquals("SPEECH_LIMIT", error.getCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertTrue(error.getMessage().contains("text only until midnight"), error.getMessage());
        // Speech already in the cache is free, and typed messages still work.
        assertArrayEquals(new byte[] {1, 2, 3}, assistant.speak(owner, first));
        assertEquals("Hello!", say("hello").reply());

        AssistantDailyUsage day = todaysRow();
        assertEquals(FREE_SPOKEN_REPLIES, day.getSpokenReplies());
        assertEquals(first.length() + second.length(), day.getSpokenCharacters());
    }

    @Test
    void speechThatFailsIsGivenBack() {
        model.failing = true;

        assertThrows(AssistantException.class, () -> assistant.speak(owner, "Not spoken " + UUID.randomUUID()));

        assertEquals(0, usageService.today(owner).spokenRepliesUsed());
    }

    @Test
    void voiceRecordingsShareTheMessagesAllowanceAndLogTheirLength() {
        for (int recording = 0; recording < FREE_MESSAGES; recording++) {
            assistant.transcribe(owner, recording());
        }

        assertEquals("DAILY_LIMIT", assertThrows(AssistantException.class,
                () -> assistant.transcribe(owner, recording())).getCode());
        assertEquals(FREE_MESSAGES, todaysRow().getTranscriptions());
        assertEquals(FREE_MESSAGES * 5, todaysRow().getTranscribedSeconds()); // 4.2 s rounds up to 5
    }

    @Test
    void aHigherPlanRaisesTheLimitsStraightAway() {
        for (int message = 0; message < FREE_MESSAGES; message++) {
            say("hello");
        }

        assertEquals(AssistantPlan.STANDARD, usageService.changePlan(owner, " standard "));

        var usage = say("one more").usage();
        assertEquals("STANDARD", usage.plan());
        assertEquals(FREE_MESSAGES + 1, usage.messagesUsed());
        assertEquals(150, usage.messagesPerDay());
        assertEquals(60, assistant.status(owner).usage().spokenRepliesPerDay());
    }

    @Test
    void anUnknownPlanIsRefusedAndAnUnknownNameInTheDatabaseMeansFree() {
        assertThrows(IllegalArgumentException.class, () -> usageService.changePlan(owner, "GOLD"));
        assertEquals(AssistantPlan.FREE, AssistantPlan.of("gold"));
        assertEquals(AssistantPlan.FREE, AssistantPlan.of(null));
    }

    @Test
    void theAdminViewListsTheMostExpensiveAccountsFirst() {
        Long quiet = owner;
        say("hello");

        owner = users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Busy")).getId();
        say("hello");
        say("hello");
        assistant.transcribe(owner, recording());
        assistant.speak(owner, "x".repeat(1000) + UUID.randomUUID());

        var accounts = usageService.topAccounts(30, 10);

        assertEquals(List.of(owner, quiet), accounts.stream().map(account -> account.userId()).toList());
        var busy = accounts.getFirst();
        assertEquals(2, busy.messages());
        assertEquals(1, busy.voiceRecordings());
        assertEquals(1, busy.spokenReplies());
        // 400 fresh + 2,000 cached input tokens, 160 output, 5 s of audio, 1,036 characters of speech.
        double expected = 400 * 0.10 / 1e6 + 2000 * 0.01 / 1e6 + 160 * 0.50 / 1e6
                + 5 / 60.0 * 0.0045 + 1036 / 1000.0 * 0.017;
        assertEquals(BigDecimal.valueOf(expected).setScale(4, java.math.RoundingMode.HALF_UP), busy.estimatedCostUsd());
        assertEquals(1, usageService.topAccounts(30, 1).size());
    }
}
