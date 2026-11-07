package app.dto;

import app.document.StallDocumentRepository;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record DocumentSummaryResponse(
        Long id,
        String title,
        @Schema(description = "TYPED, UPLOAD, or ASSISTANT for a note saved from the assistant", example = "TYPED") String source,
        @Schema(description = "The uploaded file's name; null unless uploaded") String fileName,
        @Schema(description = "PENDING, INDEXED (the assistant can search it) or FAILED", example = "INDEXED") String indexStatus,
        @Schema(description = "Length of the text in characters") int characters,
        Instant updatedAt
) {
    public static DocumentSummaryResponse of(StallDocumentRepository.Summary summary) {
        return new DocumentSummaryResponse(summary.getId(), summary.getTitle(), summary.getSource().name(),
                summary.getFileName(), summary.getIndexStatus().name(),
                summary.getCharacters() == null ? 0 : summary.getCharacters(), summary.getUpdatedAt());
    }
}
