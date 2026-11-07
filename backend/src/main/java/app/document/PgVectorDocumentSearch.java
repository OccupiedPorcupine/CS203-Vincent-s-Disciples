package app.document;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.StringJoiner;

// Postgres with the pgvector extension (Supabase). Searches with the cosine distance operator <=>,
// which the HNSW index on stall_document_chunks.embedding serves. Type and operator are qualified
// with the extension's schema ("extensions" on Supabase), so the search path doesn't matter.
class PgVectorDocumentSearch implements DocumentSearch {

    private final JdbcTemplate jdbc;
    private final String vectorType;
    private final String distance;

    PgVectorDocumentSearch(JdbcTemplate jdbc, int dimensions) {
        this.jdbc = jdbc;
        String schema = jdbc.queryForObject("SELECT quote_ident(n.nspname) FROM pg_extension e "
                + "JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'vector'", String.class);
        this.vectorType = schema + ".vector";
        this.distance = "OPERATOR(" + schema + ".<=>)";

        Integer columnDimensions = jdbc.queryForObject("SELECT atttypmod FROM pg_attribute "
                + "WHERE attrelid = 'stall_document_chunks'::regclass AND attname = 'embedding'", Integer.class);
        if (columnDimensions == null || columnDimensions != dimensions) {
            throw new IllegalStateException("app.documents.embedding-dimensions is " + dimensions
                    + " but stall_document_chunks.embedding has " + columnDimensions + ". Changing it needs a migration.");
        }
    }

    @Override
    public void replaceChunks(Long ownerId, Long documentId, List<String> contents, List<float[]> vectors, String model) {
        deleteChunks(documentId);
        for (int i = 0; i < contents.size(); i++) {
            jdbc.update("INSERT INTO stall_document_chunks (document_id, owner_id, chunk_index, content, embedding_model, embedding) "
                    + "VALUES (?, ?, ?, ?, ?, CAST(? AS " + vectorType + "))",
                    documentId, ownerId, i, contents.get(i), model, literal(vectors.get(i)));
        }
    }

    @Override
    public void deleteChunks(Long documentId) {
        jdbc.update("DELETE FROM stall_document_chunks WHERE document_id = ?", documentId);
    }

    @Override
    public List<Hit> top(Long ownerId, float[] query, String model, int k, double minSimilarity) {
        String vector = literal(query);
        return jdbc.query("SELECT c.id, c.document_id, d.title, c.content, "
                        + "1 - (c.embedding " + distance + " CAST(? AS " + vectorType + ")) AS score "
                        + "FROM stall_document_chunks c JOIN stall_documents d ON d.id = c.document_id "
                        + "WHERE c.owner_id = ? AND d.owner_id = ? AND c.embedding_model = ? "
                        + "ORDER BY c.embedding " + distance + " CAST(? AS " + vectorType + ") LIMIT ?",
                        (row, i) -> new Hit(row.getLong("id"), row.getLong("document_id"), row.getString("title"),
                                row.getString("content"), row.getDouble("score")),
                        vector, ownerId, ownerId, model, vector, k)
                .stream().filter(hit -> hit.score() >= minSimilarity).toList();
    }

    @Override
    public List<Long> documentsEmbeddedWithOtherModels(String model) {
        return jdbc.queryForList("SELECT DISTINCT document_id FROM stall_document_chunks WHERE embedding_model <> ?",
                Long.class, model);
    }

    // pgvector's text form: [0.1,0.2,...].
    private static String literal(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) joiner.add(Float.toString(value));
        return joiner.toString();
    }
}
