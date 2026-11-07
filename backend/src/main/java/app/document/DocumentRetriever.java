package app.document;

import app.assistant.AssistantUsageService;
import app.document.DocumentSearch.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Finds the parts of the owner's documents that may answer a message (issue #153), for the
// assistant's instructions and its search_documents tool. A stall without indexed documents
// costs nothing: no embedding call is made. If the embedding model fails, the assistant answers
// without documents rather than not at all.
@Service
public class DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(DocumentRetriever.class);

    // [doc:12], or several in one bracket: [doc:12, doc:14].
    private static final Pattern TAGS = Pattern.compile("\\s*\\[(doc:\\d+(?:\\s*[,;]\\s*doc:\\d+)*)]");
    private static final Pattern TAG_ID = Pattern.compile("doc:(\\d+)");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([.,;:!?。，！？])");
    static final int MAX_QUERY_CHARACTERS = 2000;

    private final StallDocumentRepository documents;
    private final DocumentSearch search;
    private final EmbeddingClient embeddings;
    private final AssistantUsageService usage;
    private final JsonMapper jsonMapper;
    private final int topK;

    public DocumentRetriever(StallDocumentRepository documents, DocumentSearch search, EmbeddingClient embeddings,
                             AssistantUsageService usage, JsonMapper jsonMapper, @Value("${app.documents.top-k}") int topK) {
        this.documents = documents;
        this.search = search;
        this.embeddings = embeddings;
        this.usage = usage;
        this.jsonMapper = jsonMapper;
        this.topK = topK;
    }

    // A reply's text without its [doc:N] tags, and the documents those tags named.
    public record Cited(String text, List<Long> documentIds) {}

    public boolean hasDocuments(Long ownerId) {
        return documents.existsByOwnerIdAndIndexStatus(ownerId, StallDocument.IndexStatus.INDEXED);
    }

    // The owner's best chunks for the query, best first; empty if none pass the similarity floor
    // or the embedding model can't be reached.
    public List<Hit> retrieve(Long ownerId, String query) {
        String text = query.strip();
        if (text.isEmpty()) return List.of();
        if (text.length() > MAX_QUERY_CHARACTERS) text = text.substring(text.length() - MAX_QUERY_CHARACTERS);
        EmbeddingClient.Embeddings embedded;
        try {
            embedded = embeddings.embed(List.of(text));
        } catch (RuntimeException exception) {
            log.warn("Documents not searched for user {}: {}", ownerId, exception.getMessage());
            return List.of();
        }
        usage.recordEmbeddingTokens(ownerId, embedded.tokens());
        List<Hit> hits = search.top(ownerId, embedded.vectors().getFirst(), embeddings.modelName(), topK, embeddings.minSimilarity());
        log.info("Documents searched for user {}: {} chunks (best {})", ownerId, hits.size(),
                hits.isEmpty() ? "none" : String.format("%.3f", hits.getFirst().score()));
        return hits;
    }

    // The chunks as the model sees them: a JSON list of {tag, title, text}. JSON keeps a document's
    // text inside its string, so nothing in it can pass for the instructions around it.
    public String json(List<Hit> hits) {
        ArrayNode list = jsonMapper.createArrayNode();
        hits.forEach(hit -> list.addObject()
                .put("tag", "doc:" + hit.documentId())
                .put("title", hit.title())
                .put("text", hit.content()));
        return jsonMapper.writeValueAsString(list);
    }

    // Removes the [doc:N] tags from a reply (they aren't for the owner to read or hear) and lists
    // the documents they name, in order, keeping only those in allowed: a tag the model made up,
    // or for a document it wasn't given, names nothing.
    public static Cited cite(String reply, Map<Long, String> allowed) {
        Map<Long, Boolean> cited = new LinkedHashMap<>();
        Matcher tags = TAGS.matcher(reply);
        while (tags.find()) {
            Matcher id = TAG_ID.matcher(tags.group(1));
            while (id.find()) {
                Long documentId = Long.valueOf(id.group(1));
                if (allowed.containsKey(documentId)) cited.put(documentId, true);
            }
        }
        String text = SPACE_BEFORE_PUNCTUATION.matcher(TAGS.matcher(reply).replaceAll("")).replaceAll("$1").strip();
        return new Cited(text, new ArrayList<>(cited.keySet()));
    }
}
