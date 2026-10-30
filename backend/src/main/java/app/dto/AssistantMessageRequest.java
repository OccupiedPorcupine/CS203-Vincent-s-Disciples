package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AssistantMessageRequest(
        @Pattern(regexp = "[A-Za-z0-9-]{1,64}") @Schema(description = "Conversation to continue; omit to start a new one") String conversationId,
        @NotBlank @Size(max = 1000) @Schema(description = "What the user said or typed", example = "today chicken rice 42, char siew 30") String text,
        @Size(max = 64) @Schema(description = "Page the user is on", example = "dashboard") String page,
        @Pattern(regexp = "voice|text") @Schema(description = "How the text was entered", example = "voice") String inputMode
) {}
