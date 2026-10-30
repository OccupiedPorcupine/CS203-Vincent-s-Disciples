package app.assistant;

import app.closure.ClosedDate;
import app.closure.ClosedDateRepository;
import app.closure.RestDayRepository;
import app.dish.Dish;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.AssistantMessageRequest;
import app.dto.AssistantMessageResponse;
import app.dto.ConfirmProposalRequest;
import app.dto.ProposalEntryInput;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// The whole assistant pipeline on H2 with the offline model (app.assistant.provider=offline in
// the test properties): message → tools → proposal → confirm/cancel.
@SpringBootTest
class AssistantServiceTest {

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-29T04:00:00Z"); // noon in Singapore

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Singapore");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    // Stubbed so the test does not call data.gov.sg on startup.
    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired AssistantService assistant;
    @Autowired AssistantTools assistantTools;
    @Autowired AppUserRepository users;
    @Autowired DishService dishService;
    @Autowired DailySaleService dailySaleService;
    @Autowired DishRepository dishRepository;
    @Autowired DishAliasRepository aliasRepository;
    @Autowired DailySaleRepository saleRepository;
    @Autowired AssistantInteractionRepository interactionRepository;
    @Autowired MutableClock clock;
    @Autowired StallClosureService closureService;
    @Autowired ClosedDateRepository closedDateRepository;
    @Autowired RestDayRepository restDayRepository;

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    Long owner;
    Dish chickenRice;
    Dish charSiewRice;

    @BeforeEach
    void setUp() {
        clock.now = Instant.parse("2026-10-29T04:00:00Z");
        owner = newUser();
        chickenRice = dishService.createDish("Chicken rice", owner);
        charSiewRice = dishService.createDish("Char siew rice", owner);
    }

    // Other tests share this database and delete users, so remove everything that points at them.
    @AfterEach
    void cleanUp() {
        interactionRepository.deleteAll();
        closedDateRepository.deleteAll();
        restDayRepository.deleteAll();
        aliasRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
    }

    private Long newUser() {
        return users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
    }

    private AssistantMessageResponse say(Long user, String text) {
        return assistant.handleMessage(user, new AssistantMessageRequest(null, text, "dashboard", "text", null));
    }

    @Test
    void eachMessageIsLabelledEnglishMandarinOrMixed() {
        say(owner, "today chicken rice 42");
        say(owner, "今天鸡饭四十二");
        say(owner, "今天 chicken rice 42");
        say(owner, "42");

        var labels = interactionRepository.findAll().stream().map(AssistantInteraction::getDetectedLanguages).toList();
        assertEquals(4, labels.size());
        assertTrue(labels.containsAll(List.of("en", "zh", "mixed")));
        assertTrue(labels.contains(null));
    }

    @Test
    void aMessageProposesSalesWithoutSavingAnything() {
        var response = say(owner, "today chicken rice 42, char siew 30, laksa 12");

        assertNotNull(response.conversationId());
        assertEquals("Please check these and press Confirm to save them.", response.reply());
        var entries = response.proposal().entries();
        assertEquals(3, entries.size());
        assertEquals(chickenRice.getId(), entries.get(0).dishId());
        assertEquals(charSiewRice.getId(), entries.get(1).dishId());
        assertTrue(entries.get(2).isNewDish());
        assertEquals("laksa", entries.get(2).newDishName());
        assertTrue(entries.stream().allMatch(entry -> entry.date().equals(TODAY)));

        assertTrue(saleRepository.findByDishIdOrderBySaleDateAsc(chickenRice.getId()).isEmpty());
        assertEquals(2, dishRepository.findByOwnerIdOrderByNameAsc(owner).size());
        assertEquals(AssistantInteraction.Outcome.PENDING,
                interactionRepository.findById(response.proposal().id()).orElseThrow().getOutcome());
    }

    @Test
    void confirmingSavesSalesCreatesNewDishesAndRemembersNicknamesOnce() {
        var proposal = say(owner, "yesterday chicken rice 42, char siew 30, laksa 12").proposal();

        var result = assistant.confirmProposal(owner, proposal.id(), null);

        assertEquals("CONFIRMED", result.outcome());
        assertEquals(42, saleRepository.findByDishIdAndSaleDate(chickenRice.getId(), TODAY.minusDays(1)).orElseThrow().getQuantitySold());
        var laksa = dishRepository.findByOwnerIdOrderByNameAsc(owner).stream().filter(d -> d.getName().equals("laksa")).findFirst();
        assertTrue(laksa.isPresent());
        assertEquals(List.of("char siew"),
                aliasRepository.findByDishIdOrderByAliasAsc(charSiewRice.getId()).stream().map(a -> a.getAlias()).toList());

        // Confirming again returns the same result and saves nothing more.
        var again = assistant.confirmProposal(owner, proposal.id(), null);
        assertEquals(result.saved(), again.saved());
        assertEquals(3, dishRepository.findByOwnerIdOrderByNameAsc(owner).size());
    }

    @Test
    void aCorrectionShowsAndReplacesThePreviousQuantity() {
        dailySaleService.setSale(chickenRice.getId(), owner, TODAY, 40);

        var proposal = say(owner, "chicken rice 45").proposal();
        assertEquals(40, proposal.entries().getFirst().previousQuantity());

        var result = assistant.confirmProposal(owner, proposal.id(), null);
        assertEquals(40, result.saved().getFirst().previousQuantity());
        assertEquals(45, saleRepository.findByDishIdAndSaleDate(chickenRice.getId(), TODAY).orElseThrow().getQuantitySold());
    }

    @Test
    void editingBeforeConfirmingSavesTheEditAndRecordsIt() {
        var proposal = say(owner, "chicken rice 42").proposal();
        var edited = new ConfirmProposalRequest(List.of(
                new ProposalEntryInput(chickenRice.getId(), null, "chicken rice", TODAY, 44)));

        var result = assistant.confirmProposal(owner, proposal.id(), edited);

        assertEquals("EDITED", result.outcome());
        assertEquals(44, saleRepository.findByDishIdAndSaleDate(chickenRice.getId(), TODAY).orElseThrow().getQuantitySold());
    }

    @Test
    void anEditCannotSaveAFutureDateOrAnotherUsersDish() {
        var proposal = say(owner, "chicken rice 42").proposal();
        Long otherUser = newUser();
        Dish othersDish = dishService.createDish("Laksa", otherUser);

        var future = new ConfirmProposalRequest(List.of(new ProposalEntryInput(chickenRice.getId(), null, null, TODAY.plusDays(1), 5)));
        var notMine = new ConfirmProposalRequest(List.of(new ProposalEntryInput(othersDish.getId(), null, null, TODAY, 5)));

        assertEquals("INVALID_PROPOSAL", assertThrows(AssistantException.class, () -> assistant.confirmProposal(owner, proposal.id(), future)).getCode());
        assertEquals("INVALID_PROPOSAL", assertThrows(AssistantException.class, () -> assistant.confirmProposal(owner, proposal.id(), notMine)).getCode());
        assertTrue(saleRepository.findByDishIdOrderBySaleDateAsc(othersDish.getId()).isEmpty());
    }

    @Test
    void anotherUserCannotConfirmOrCancelAProposal() {
        var proposal = say(owner, "chicken rice 42").proposal();
        Long otherUser = newUser();

        var error = assertThrows(AssistantException.class, () -> assistant.confirmProposal(otherUser, proposal.id(), null));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertThrows(AssistantException.class, () -> assistant.cancelProposal(otherUser, proposal.id()));
        assertTrue(saleRepository.findByDishIdOrderBySaleDateAsc(chickenRice.getId()).isEmpty());
    }

    @Test
    void anExpiredProposalCannotBeConfirmed() {
        var proposal = say(owner, "chicken rice 42").proposal();
        clock.now = clock.now.plus(AssistantService.PROPOSAL_LIFETIME).plusSeconds(1);

        var error = assertThrows(AssistantException.class, () -> assistant.confirmProposal(owner, proposal.id(), null));

        assertEquals("PROPOSAL_CLOSED", error.getCode());
        assertEquals(AssistantInteraction.Outcome.EXPIRED, interactionRepository.findById(proposal.id()).orElseThrow().getOutcome());
        assertTrue(saleRepository.findByDishIdOrderBySaleDateAsc(chickenRice.getId()).isEmpty());
    }

    @Test
    void aCancelledProposalCannotBeConfirmed() {
        var proposal = say(owner, "chicken rice 42").proposal();

        assistant.cancelProposal(owner, proposal.id());

        assertEquals(HttpStatus.CONFLICT, assertThrows(AssistantException.class,
                () -> assistant.confirmProposal(owner, proposal.id(), null)).getStatus());
        assertTrue(saleRepository.findByDishIdOrderBySaleDateAsc(chickenRice.getId()).isEmpty());
    }

    @Test
    void aClosureIsProposedWithoutSavingAnything() {
        var response = say(owner, "closed tomorrow for centre cleaning");

        assertEquals("Please check these and press Confirm to save them.", response.reply());
        assertTrue(response.proposal().entries().isEmpty());
        assertEquals(List.of(new ProposedClosure(TODAY.plusDays(1), true, "Centre cleaning")), response.proposal().closures());
        assertTrue(closedDateRepository.findByOwnerIdOrderByClosedDateAsc(owner).isEmpty());
    }

    @Test
    void salesAndAClosureShareOneCardAndAreSavedTogether() {
        var proposal = say(owner, "today chicken rice 42, closed tomorrow").proposal();
        assertEquals(1, proposal.entries().size());
        assertEquals(1, proposal.closures().size());

        var result = assistant.confirmProposal(owner, proposal.id(), null);

        assertEquals("CONFIRMED", result.outcome());
        assertEquals(42, saleRepository.findByDishIdAndSaleDate(chickenRice.getId(), TODAY).orElseThrow().getQuantitySold());
        assertEquals(List.of(TODAY.plusDays(1)), closedDates());
        assertEquals(result.closures(), assistant.confirmProposal(owner, proposal.id(), null).closures());
    }

    @Test
    void removingEverySaleFromTheCardStillSavesTheClosure() {
        var proposal = say(owner, "today chicken rice 42, closed tomorrow").proposal();

        var result = assistant.confirmProposal(owner, proposal.id(), new ConfirmProposalRequest(List.of()));

        assertEquals("EDITED", result.outcome());
        assertTrue(saleRepository.findByDishIdOrderBySaleDateAsc(chickenRice.getId()).isEmpty());
        assertEquals(List.of(TODAY.plusDays(1)), closedDates());
    }

    @Test
    void closingADayAgainWithoutAReasonKeepsTheEarlierReason() {
        closureService.closeDate(owner, TODAY.plusDays(1), "Centre cleaning");

        assistant.confirmProposal(owner, say(owner, "closed tomorrow").proposal().id(), null);

        assertEquals("Centre cleaning", closedDateRepository.findByOwnerIdAndClosedDate(owner, TODAY.plusDays(1)).orElseThrow().getReason());
    }

    @Test
    void openingAgainNeedsADayThatIsMarkedClosed() {
        var nothingToOpen = say(owner, "we will open tomorrow after all");
        assertNull(nothingToOpen.proposal());
        assertTrue(nothingToOpen.reply().contains("isn't marked as closed"), nothingToOpen.reply());

        closureService.closeDate(owner, TODAY.plusDays(1), "Holiday");
        var proposal = say(owner, "we will open tomorrow after all").proposal();
        assertEquals(List.of(new ProposedClosure(TODAY.plusDays(1), false, null)), proposal.closures());

        assistant.confirmProposal(owner, proposal.id(), null);
        assertTrue(closedDates().isEmpty());
    }

    @Test
    void weeklyRestDaysAreNotChangedThroughTheAssistant() {
        closureService.setRestDays(owner, List.of(TODAY.plusDays(1).getDayOfWeek()));

        var response = say(owner, "closed tomorrow");

        assertNull(response.proposal());
        assertTrue(response.reply().contains("weekly rest day"), response.reply());
    }

    @Test
    void anotherUserCannotSeeOrConfirmAClosure() {
        var proposal = say(owner, "closed tomorrow").proposal();

        var error = assertThrows(AssistantException.class, () -> assistant.confirmProposal(newUser(), proposal.id(), null));

        assertEquals("PROPOSAL_NOT_FOUND", error.getCode());
        assertTrue(closedDates().isEmpty());
    }

    private List<LocalDate> closedDates() {
        return closedDateRepository.findByOwnerIdOrderByClosedDateAsc(owner).stream().map(ClosedDate::getClosedDate).toList();
    }

    @Test
    void aMessageWithoutSalesGetsAReplyAndNoProposal() {
        var response = say(owner, "what's the weather like?");

        assertNull(response.proposal());
        assertTrue(response.reply().startsWith("Offline demo mode"));
    }

    @Test
    void aHowToQuestionStartsAGuideFromTheCurrentPage() {
        var response = say(owner, "how do I upload my sales data?");

        assertNull(response.proposal());
        assertEquals("Let me show you.", response.reply());
        assertEquals(List.of("nav-data", "upload-dropzone", "upload-button", "source-list"),
                response.guide().stream().map(step -> step.guideId()).toList());
        assertEquals("data.html", response.guide().get(1).url());
    }

    @Test
    void customGuideStepsMustPointAtKnownOrVisibleElements() {
        var context = new AssistantTools.TurnContext(owner, TODAY, "data", java.util.Set.of("live-3"));
        var jsonMapper = tools.jackson.databind.json.JsonMapper.builder().build();
        java.util.function.Function<String, AssistantModelClient.ToolCall> guideCall = steps ->
                new AssistantModelClient.ToolCall("c1", AssistantTools.GUIDE_USER,
                        jsonMapper.readTree("{\"task_id\": null, \"steps\": " + steps + "}"));

        String unknown = assistantTools.execute(guideCall.apply("[{\"page\": \"data\", \"guide_id\": \"delete-everything\", \"message\": \"Click\"}]"), context);
        assertTrue(unknown.contains("No element delete-everything"));
        assertNull(context.guide());

        String liveOnOtherPage = assistantTools.execute(guideCall.apply("[{\"page\": \"forecast\", \"guide_id\": \"live-3\", \"message\": \"Here\"}]"), context);
        assertTrue(liveOnOtherPage.contains("\"ok\":false"));

        String live = assistantTools.execute(guideCall.apply("[{\"page\": \"data\", \"guide_id\": \"live-3\", \"message\": \"This one\"}]"), context);
        assertTrue(live.contains("\"ok\":true"));
        assertEquals("live-3", context.guide().getFirst().guideId());
    }

    @Test
    void voiceIsUnavailableOfflineAndUnsupportedAudioIsRejected() {
        var webm = new MockMultipartFile("audio", "speech.webm", "audio/webm;codecs=opus", new byte[]{1});
        var text = new MockMultipartFile("audio", "notes.txt", "text/plain", new byte[]{1});

        assertEquals("VOICE_UNAVAILABLE", assertThrows(AssistantException.class, () -> assistant.transcribe(owner, webm)).getCode());
        assertEquals("VOICE_UNAVAILABLE", assertThrows(AssistantException.class, () -> assistant.speak(owner, "Hello")).getCode());
        assertEquals("UNSUPPORTED_AUDIO", assertThrows(AssistantException.class, () -> assistant.transcribe(owner, text)).getCode());
    }
}
