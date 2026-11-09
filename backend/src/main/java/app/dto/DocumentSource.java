package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// A document an assistant reply was answered from (issue #153). The web links it to
// documents.html#document-{documentId}; chat apps add "(from: title)".
public record DocumentSource(
        @Schema(example = "12") Long documentId,
        @Schema(example = "Ah Huat Poultry") String title
) {}
