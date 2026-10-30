package app.dto;

import app.assistant.ProposedClosure;
import app.assistant.ProposedEntry;
import app.assistant.guide.GuideStep;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record AssistantMessageResponse(
        @Schema(description = "Send this back to continue the conversation") String conversationId,
        @Schema(description = "The assistant's reply", example = "Please check these and press Confirm to save them.") String reply,
        @Schema(description = "Sales and closed days to show on a confirm card; null if the assistant proposed neither") Proposal proposal,
        @Schema(description = "Guided tour to show; null if none") List<GuideStep> guide
) {
    public record Proposal(
            @Schema(description = "Proposal ID for confirm or cancel", example = "12") Long id,
            @Schema(description = "Proposed sales; empty if only closed days were proposed") List<ProposedEntry> entries,
            @Schema(description = "Proposed changes to closed days; empty if only sales were proposed") List<ProposedClosure> closures,
            @Schema(description = "After this the proposal can no longer be confirmed") Instant expiresAt
    ) {}
}
