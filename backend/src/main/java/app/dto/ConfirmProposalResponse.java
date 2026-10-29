package app.dto;

import app.assistant.ProposedEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ConfirmProposalResponse(
        @Schema(description = "CONFIRMED if saved as proposed, EDITED if the user changed it first", example = "CONFIRMED") String outcome,
        @Schema(description = "What was saved; previousQuantity shows any quantity that was replaced") List<ProposedEntry> saved
) {}
