package app.document;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

// Indexing and search for stall documents (issue #152) with the offline embedding client and H2.
@SpringBootTest
class DocumentIndexingTest {

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @MockitoSpyBean
    EmbeddingClient embeddings;

    @Autowired
    DocumentService documents;

    @Autowired
    DocumentSearch search;

    @Autowired
    DocumentIndexer indexer;

    @Autowired
    AppUserRepository users;

    @Autowired
    JdbcTemplate jdbc;

    private Long ownerA;
    private Long ownerB;

    @BeforeEach
    void createOwners() {
        ownerA = users.save(AppUser.localUser("a-" + UUID.randomUUID() + "@index.test", "{noop}x", "A")).getId();
        ownerB = users.save(AppUser.localUser("b-" + UUID.randomUUID() + "@index.test", "{noop}x", "B")).getId();
    }

    @AfterEach
    void clean() {
        reset(embeddings);
        jdbc.update("DELETE FROM stall_documents");
    }

    @Test
    void aSavedDocumentIsChunkedAndEmbedded() {
        var created = documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm. Call 9123 4567.");

        assertEquals("INDEXED", created.indexStatus());
        assertEquals(List.of("Order chicken by 9pm. Call 9123 4567."), chunks(created.id()));
        assertEquals("offline-hashed-words", jdbc.queryForObject(
                "SELECT embedding_model FROM stall_document_chunks WHERE document_id = ?", String.class, created.id()));
    }

    @Test
    void editingTheTextReplacesItsChunks() {
        var created = documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm.");

        documents.update(ownerA, created.id(), "Ah Huat Poultry", "Order chicken by 8pm now.");

        assertEquals(List.of("Order chicken by 8pm now."), chunks(created.id()));
        assertEquals(1, hits(ownerA, "when do I order chicken").size());
    }

    @Test
    void deletingADocumentDeletesItsChunks() {
        var created = documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm.");

        documents.delete(ownerA, created.id());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM stall_document_chunks WHERE document_id = ?", Integer.class, created.id()));
    }

    @Test
    void aDocumentIsKeptAndMarkedFailedWhenEmbeddingFails() {
        var created = documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm.");
        doThrow(new EmbeddingException("down")).when(embeddings).embed(anyList());

        var edited = documents.update(ownerA, created.id(), "Ah Huat Poultry", "Order chicken by 8pm now.");

        assertEquals("FAILED", edited.indexStatus());
        assertEquals("Order chicken by 8pm now.", edited.body());
        assertTrue(chunks(created.id()).isEmpty(), "Chunks of the old text aren't left to answer from");

        reset(embeddings);
        assertEquals("INDEXED", documents.reindex(ownerA, created.id()).indexStatus());
        assertEquals(List.of("Order chicken by 8pm now."), chunks(created.id()));
    }

    @Test
    void searchNeverReturnsAnotherOwnersChunks() {
        documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm from Ah Huat. Chicken wings and thighs.");
        var eggs = documents.create(ownerB, "Kim Lee Eggs", "Order eggs by 6pm from Kim Lee.");

        List<DocumentSearch.Hit> forB = hits(ownerB, "who do I call to order chicken wings by 9pm");

        assertTrue(forB.stream().allMatch(hit -> hit.documentId().equals(eggs.id())), forB.toString());
        assertFalse(hits(ownerA, "order chicken wings").isEmpty());
    }

    @Test
    void theBestMatchComesFirstAndUnrelatedTextIsLeftOut() {
        var poultry = documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm. Call 9123 4567.");
        documents.create(ownerA, "Gas", "When the gas runs out, call Seng Gas on 6123 4567 and switch to the spare tank.");

        List<DocumentSearch.Hit> hits = hits(ownerA, "when must I order chicken from Ah Huat Poultry?");

        assertEquals(poultry.id(), hits.getFirst().documentId());
        assertTrue(hits(ownerA, "durian season prices").isEmpty());
    }

    @Test
    void documentsFromAnotherModelAreEmbeddedAgainOnStartup() {
        var created = documents.create(ownerA, "Ah Huat Poultry", "Order chicken by 9pm.");
        jdbc.update("UPDATE stall_document_chunks SET embedding_model = 'old-model' WHERE document_id = ?", created.id());

        indexer.reindexStale();

        assertEquals("offline-hashed-words", jdbc.queryForObject(
                "SELECT embedding_model FROM stall_document_chunks WHERE document_id = ?", String.class, created.id()));
    }

    private List<DocumentSearch.Hit> hits(Long ownerId, String question) {
        float[] query = embeddings.embed(List.of(question)).vectors().getFirst();
        return search.top(ownerId, query, embeddings.modelName(), 5, embeddings.minSimilarity());
    }

    private List<String> chunks(Long documentId) {
        return jdbc.queryForList("SELECT content FROM stall_document_chunks WHERE document_id = ? ORDER BY chunk_index",
                String.class, documentId);
    }
}
