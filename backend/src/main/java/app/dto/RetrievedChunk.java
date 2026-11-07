package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// A part of one of the owner's documents that document search found (issues #153, #155).
public record RetrievedChunk(
        Long documentId,
        String title,
        Long chunkId,
        @Schema(description = "Cosine similarity to the question, -1 to 1", example = "0.52") double score,
        @Schema(description = "message (found with the user's message) or tool (search_documents); null from /api/documents/search") String via,
        @Schema(description = "The part's text") String text
) {}
