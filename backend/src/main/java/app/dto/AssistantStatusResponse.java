package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AssistantStatusResponse(
        @Schema(description = "Whether the assistant can answer messages") boolean available,
        @Schema(description = "Whether voice input is available") boolean voice,
        @Schema(description = "Model in use", example = "gpt-6-luna") String model
) {}
