package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record TranscriptionResponse(
        @Schema(description = "What the user said", example = "今天 chicken rice 42") String text,
        @Schema(description = "Detected languages; empty if unsure", example = "[\"zh\", \"en\"]") List<String> languages
) {}
