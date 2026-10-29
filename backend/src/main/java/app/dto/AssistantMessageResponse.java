package app.dto;

import app.assistant.ProposedEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record AssistantMessageResponse(
        @Schema(description = "Send this back to continue the conversation") String conversationId,
        @Schema(description = "The assistant's reply", example = "Please check these and press Confirm to save them.") String reply,
        @Schema(description = "Sales to show on a confirm card; null if the assistant proposed none") Proposal proposal
) {
    public record Proposal(
            @Schema(description = "Proposal ID for confirm or cancel", example = "12") Long id,
            List<ProposedEntry> entries,
            @Schema(description = "After this the proposal can no longer be confirmed") Instant expiresAt
    ) {}
}
