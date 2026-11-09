package app.document;

import java.util.List;

// Turns text into vectors for document search (issue #152). Picked by app.assistant.provider, like
// the chat model: OpenAI's embeddings, or a deterministic offline stand-in for tests and CI.
public interface EmbeddingClient {

    // Stored with each chunk, so chunks from another model are re-embedded rather than compared.
    String modelName();

    int dimensions();

    // Search results under this cosine similarity are dropped (app.documents.min-similarity).
    double minSimilarity();

    // One vector per text, in order, in one call. Throws EmbeddingException if it can't.
    Embeddings embed(List<String> texts);

    // tokens: what the API reported, for the usage figures; 0 for the offline client.
    record Embeddings(List<float[]> vectors, long tokens) {}
}
