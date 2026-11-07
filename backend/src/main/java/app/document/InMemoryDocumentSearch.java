package app.document;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;

// H2 (local development and tests) has no vector type: embeddings are stored as bytes, and the
// owner's chunks are scored by cosine similarity in Java. A stall has at most 30 documents, so
// this stays small.
class InMemoryDocumentSearch implements DocumentSearch {

    private final JdbcTemplate jdbc;

    InMemoryDocumentSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replaceChunks(Long ownerId, Long documentId, List<String> contents, List<float[]> vectors, String model) {
        deleteChunks(documentId);
        for (int i = 0; i < contents.size(); i++) {
            jdbc.update("INSERT INTO stall_document_chunks (document_id, owner_id, chunk_index, content, embedding_model, embedding) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", documentId, ownerId, i, contents.get(i), model, bytes(vectors.get(i)));
        }
    }

    @Override
    public void deleteChunks(Long documentId) {
        jdbc.update("DELETE FROM stall_document_chunks WHERE document_id = ?", documentId);
    }

    @Override
    public List<Hit> top(Long ownerId, float[] query, String model, int k, double minSimilarity) {
        return jdbc.query("SELECT c.id, c.document_id, d.title, c.content, c.embedding "
                                + "FROM stall_document_chunks c JOIN stall_documents d ON d.id = c.document_id "
                                + "WHERE c.owner_id = ? AND d.owner_id = ? AND c.embedding_model = ?",
                        (row, i) -> new Hit(row.getLong("id"), row.getLong("document_id"), row.getString("title"),
                                row.getString("content"), cosine(query, floats(row.getBytes("embedding")))),
                        ownerId, ownerId, model)
                .stream()
                .filter(hit -> hit.score() >= minSimilarity)
                .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::chunkId))
                .limit(k)
                .toList();
    }

    @Override
    public List<Long> documentsEmbeddedWithOtherModels(String model) {
        return jdbc.queryForList("SELECT DISTINCT document_id FROM stall_document_chunks WHERE embedding_model <> ?",
                Long.class, model);
    }

    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / Math.sqrt(normA * normB);
    }

    private static byte[] bytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    private static float[] floats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }
}
