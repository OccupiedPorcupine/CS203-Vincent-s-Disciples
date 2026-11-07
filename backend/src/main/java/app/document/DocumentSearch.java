package app.document;

import java.util.List;

// Where document chunks and their vectors are stored and searched (issue #152): pgvector on
// Postgres/Supabase, or cosine similarity in Java on H2. Every search is filtered by owner, which
// is what keeps one stall's documents from ever reaching another's assistant.
public interface DocumentSearch {

    // Replaces a document's chunks with these (the same number of contents and vectors).
    void replaceChunks(Long ownerId, Long documentId, List<String> contents, List<float[]> vectors, String model);

    void deleteChunks(Long documentId);

    // The owner's k chunks most similar to the query, best first, from chunks embedded with this
    // model, leaving out any under minSimilarity.
    List<Hit> top(Long ownerId, float[] query, String model, int k, double minSimilarity);

    // Documents with chunks from a model other than this one, which need embedding again.
    List<Long> documentsEmbeddedWithOtherModels(String model);

    // score is the cosine similarity, from -1 to 1.
    record Hit(Long chunkId, Long documentId, String title, String content, double score) {}
}
