package app.assistant;

import app.chat.ChatChannel;
import app.chat.ChatInput;
import app.chat.ChatLink;
import app.chat.ChatLinkRepository;
import app.chat.ChatMessageService;
import app.chat.ChatReply;
import app.document.DocumentService;
import app.dto.AssistantMessageRequest;
import app.dto.AssistantMessageResponse;
import app.dto.ConfirmProposalRequest;
import app.service.PublicHolidaySyncService;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// "Remember that ..." saves a note to the owner's documents behind a confirm card (issue #154),
// with the offline model and offline embeddings on H2.
@SpringBootTest
class AssistantNotesTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired
    AssistantService assistant;

    @Autowired
    DocumentService documents;

    @Autowired
    ChatMessageService chat;

    @Autowired
    ChatLinkRepository links;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private Long owner;

    @BeforeEach
    void createOwner() {
        owner = users.save(AppUser.localUser("notes-" + UUID.randomUUID() + "@notes.test", "{noop}x", "Owner")).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM stall_documents");
    }

    @Test
    void aConfirmedNoteBecomesAnIndexedDocumentTheAssistantAnswersFrom() {
        AssistantMessageResponse response = ask("Remember that the fishball supplier is closed on Tuesdays");

        assertEquals("NOTE", response.proposal().type());
        assertEquals("Fishball supplier is closed on Tuesdays", response.proposal().note().title());
        assertEquals("The fishball supplier is closed on Tuesdays.", response.proposal().note().text());
        assertEquals(0, documentCount(), "Nothing is saved before confirming");

        var confirmed = assistant.confirmProposal(owner, response.proposal().id(), null);

        assertEquals("CONFIRMED", confirmed.outcome());
        Map<String, Object> saved = jdbc.queryForMap("SELECT id, source, index_status, body FROM stall_documents WHERE owner_id = ?", owner);
        assertEquals(confirmed.note().documentId(), ((Number) saved.get("id")).longValue());
        assertEquals("ASSISTANT", saved.get("source"));
        assertEquals("INDEXED", saved.get("index_status"));

        AssistantMessageResponse answer = ask("Is the fishball supplier open on Tuesday?");
        assertTrue(answer.reply().contains("closed on Tuesdays"), answer.reply());
        assertEquals(confirmed.note().documentId(), answer.sources().getFirst().documentId());

        assertEquals("CONFIRMED", assistant.confirmProposal(owner, response.proposal().id(), null).outcome());
        assertEquals(1, documentCount(), "Confirming twice saves once");
    }

    @Test
    void theNoteCanBeEditedOnTheCard() {
        AssistantMessageResponse response = ask("note down: gas man is Ah Seng 9123 4567");

        var confirmed = assistant.confirmProposal(owner, response.proposal().id(),
                new ConfirmProposalRequest(null, null, null, "Gas man", "Ah Seng, 9123 4567. Comes on Mondays."));

        assertEquals("EDITED", confirmed.outcome());
        assertEquals("Ah Seng, 9123 4567. Comes on Mondays.", jdbc.queryForObject(
                "SELECT body FROM stall_documents WHERE id = ?", String.class, confirmed.note().documentId()));
    }

    @Test
    void cancellingOrLettingTheCardExpireSavesNothing() {
        AssistantMessageResponse cancelled = ask("Remember that the ice man comes at 7am");
        assistant.cancelProposal(owner, cancelled.proposal().id());

        AssistantMessageResponse expired = ask("Remember that the rice is from Thong Seng");
        jdbc.update("UPDATE assistant_interactions SET created_at = DATEADD('MINUTE', -20, created_at) WHERE id = ?", expired.proposal().id());

        assertThrows(AssistantException.class, () -> assistant.confirmProposal(owner, cancelled.proposal().id(), null));
        assertThrows(AssistantException.class, () -> assistant.confirmProposal(owner, expired.proposal().id(), null));
        assertEquals(0, documentCount());
    }

    @Test
    void theStallsOwnClosedDayIsAClosureNotANote() {
        AssistantMessageResponse response = ask("Remember that we are closed tomorrow");

        assertNotNull(response.proposal());
        assertNotEquals("NOTE", response.proposal().type());
        assertEquals(1, response.proposal().closures().size());
    }

    @Test
    void aDishsOtherNameIsNotANote() {
        AssistantMessageResponse response = ask("Remember that CR means chicken rice");

        assertNull(response.proposal());
        assertTrue(response.reply().contains("record sales"), response.reply());
    }

    @Test
    void aFullStallIsToldWhyTheNoteCantBeSaved() {
        AssistantMessageResponse pending = ask("Remember that the ice man comes at 7am");
        for (int i = 1; i <= 30; i++) documents.create(owner, "Note " + i, "Text " + i);

        AssistantMessageResponse full = ask("Remember that the rice is from Thong Seng");
        AssistantException refused = assertThrows(AssistantException.class,
                () -> assistant.confirmProposal(owner, pending.proposal().id(), null));

        assertNull(full.proposal());
        assertTrue(full.reply().contains("30 documents"), full.reply());
        assertEquals("TOO_MANY_DOCUMENTS", refused.getCode());
        assertEquals(30, documentCount());
    }

    @Test
    void aNoteWorksFromTelegramEndToEnd() {
        ChatLink link = new ChatLink(users.findById(owner).orElseThrow(), ChatChannel.TELEGRAM, "notes-" + owner, "Owner", Instant.now());
        links.save(link);

        ChatReply card = chat.handle(ChatChannel.TELEGRAM, "notes-" + owner, new ChatInput.Text("Remember that the fishball supplier is closed on Tuesdays"));
        assertEquals("Save this note?\nFishball supplier is closed on Tuesdays: The fishball supplier is closed on Tuesdays.", card.card());

        ChatReply saved = chat.confirm(ChatChannel.TELEGRAM, "notes-" + owner, card.proposalId());
        assertTrue(saved.reply().startsWith("Saved as a note: Fishball supplier is closed on Tuesdays."), saved.reply());

        ChatReply answer = chat.handle(ChatChannel.TELEGRAM, "notes-" + owner, new ChatInput.Text("Is the fishball supplier open on Tuesday?"));
        assertTrue(answer.reply().endsWith("(from: Fishball supplier is closed on Tuesdays)"), answer.reply());
    }

    private AssistantMessageResponse ask(String text) {
        return assistant.handleMessage(owner, new AssistantMessageRequest(null, text, "dashboard", "text", null, null));
    }

    private int documentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stall_documents WHERE owner_id = ?", Integer.class, owner);
    }
}
