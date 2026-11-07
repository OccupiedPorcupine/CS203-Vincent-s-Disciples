package app.dto;

import app.document.StallDocument;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record DocumentResponse(
        Long id,
        String title,
        @Schema(description = "The document's plain text") String body,
        @Schema(description = "TYPED, UPLOAD, or ASSISTANT for a note saved from the assistant", example = "UPLOAD") String source,
        @Schema(description = "The uploaded file's name; null unless uploaded") String fileName,
        @Schema(description = "PENDING, INDEXED (the assistant can search it) or FAILED", example = "INDEXED") String indexStatus,
        @Schema(description = "Length of the text in characters") int characters,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentResponse of(StallDocument document) {
        return new DocumentResponse(document.getId(), document.getTitle(), document.getBody(), document.getSource().name(),
                document.getFileName(), document.getIndexStatus().name(), document.getBody().length(),
                document.getCreatedAt(), document.getUpdatedAt());
    }
}
