package app.document;

import app.assistant.AssistantUsageService;
import app.document.StallDocument.IndexStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Chunks and embeds a document so the assistant can search it (issue #152). Runs in the request
// that saved the document (documents are small, and background threads aren't reliable on
// Vercel), outside any transaction so no database connection waits on the embedding call. If
// embedding fails, the document stays saved, marked FAILED, without chunks from its old text.
@Service
public class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    // Documents re-embedded on one startup, at most; the rest wait for the next.
    static final int MAX_REINDEX_ON_STARTUP = 100;

    private final StallDocumentRepository documents;
    private final DocumentSearch search;
    private final EmbeddingClient embeddings;
    private final AssistantUsageService usage;
    private final TransactionTemplate transactions;

    public DocumentIndexer(StallDocumentRepository documents, DocumentSearch search, EmbeddingClient embeddings,
                           AssistantUsageService usage, TransactionTemplate transactions) {
        this.documents = documents;
        this.search = search;
        this.embeddings = embeddings;
        this.usage = usage;
        this.transactions = transactions;
    }

    public IndexStatus index(Long documentId) {
        StallDocument document = documents.findById(documentId).orElse(null);
        if (document == null) return null;
        Instant version = document.getUpdatedAt();
        long started = System.nanoTime();

        List<String> chunks = DocumentChunker.chunk(document.getBody());
        EmbeddingClient.Embeddings embedded;
        try {
            embedded = embeddings.embed(chunks.stream().map(chunk -> DocumentChunker.embeddedText(document.getTitle(), chunk)).toList());
        } catch (RuntimeException exception) {
            log.warn("Document #{} for user {} not indexed: {}", documentId, document.getOwnerId(), exception.getMessage());
            save(documentId, version, IndexStatus.FAILED, () -> search.deleteChunks(documentId));
            return IndexStatus.FAILED;
        }

        boolean saved = save(documentId, version, IndexStatus.INDEXED, () -> search.replaceChunks(
                document.getOwnerId(), documentId, chunks, embedded.vectors(), embeddings.modelName()));
        usage.recordEmbeddingTokens(document.getOwnerId(), embedded.tokens());
        log.info("Document #{} for user {} indexed: {} characters in {} chunks, {} tokens, {} ms{}", documentId,
                document.getOwnerId(), document.getBody().length(), chunks.size(), embedded.tokens(),
                (System.nanoTime() - started) / 1_000_000, saved ? "" : " (edited meanwhile; left to that edit)");
        return saved ? IndexStatus.INDEXED : documents.findById(documentId).map(StallDocument::getIndexStatus).orElse(null);
    }

    // Writes the chunks and status, unless the document was edited or deleted while it was being
    // embedded: the request that edited it indexes the new text.
    private boolean save(Long documentId, Instant version, IndexStatus status, Runnable writeChunks) {
        Boolean saved = transactions.execute(transaction -> {
            StallDocument current = documents.findById(documentId).orElse(null);
            if (current == null || !current.getUpdatedAt().equals(version)) return false;
            writeChunks.run();
            current.markIndexStatus(status);
            return true;
        });
        return Boolean.TRUE.equals(saved);
    }

    // After a change of embedding model, re-embeds documents whose chunks came from the old one
    // (a change of dimensions needs a migration first), and any left PENDING by a request that died.
    @EventListener(ApplicationReadyEvent.class)
    public void reindexStale() {
        Set<Long> stale = new LinkedHashSet<>(search.documentsEmbeddedWithOtherModels(embeddings.modelName()));
        stale.addAll(documents.findIdsByIndexStatus(IndexStatus.PENDING));
        if (stale.isEmpty()) return;
        log.info("Re-indexing {} documents for embedding model {}", stale.size(), embeddings.modelName());
        stale.stream().limit(MAX_REINDEX_ON_STARTUP).forEach(id -> {
            try {
                index(id);
            } catch (RuntimeException exception) {
                log.warn("Document #{} not re-indexed: {}", id, exception.toString());
            }
        });
    }
}
