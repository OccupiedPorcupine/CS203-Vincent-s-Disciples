package app.assistant;

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
    @Autowired AppUserRepository users;
    @Autowired DishService dishService;
    @Autowired DailySaleService dailySaleService;
    @Autowired DishRepository dishRepository;
    @Autowired DishAliasRepository aliasRepository;
    @Autowired DailySaleRepository saleRepository;
    @Autowired AssistantInteractionRepository interactionRepository;
    @Autowired MutableClock clock;

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
        aliasRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
    }

    private Long newUser() {
        return users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
    }

    private AssistantMessageResponse say(Long user, String text) {
        return assistant.handleMessage(user, new AssistantMessageRequest(null, text, "dashboard", "text"));
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
    void aMessageWithoutSalesGetsAReplyAndNoProposal() {
        var response = say(owner, "what's the weather like?");

        assertNull(response.proposal());
        assertTrue(response.reply().startsWith("Offline demo mode"));
    }

    @Test
    void voiceIsUnavailableOfflineAndUnsupportedAudioIsRejected() {
        var webm = new MockMultipartFile("audio", "speech.webm", "audio/webm;codecs=opus", new byte[]{1});
        var text = new MockMultipartFile("audio", "notes.txt", "text/plain", new byte[]{1});

        assertEquals("VOICE_UNAVAILABLE", assertThrows(AssistantException.class, () -> assistant.transcribe(owner, webm)).getCode());
        assertEquals("UNSUPPORTED_AUDIO", assertThrows(AssistantException.class, () -> assistant.transcribe(owner, text)).getCode());
    }
}
