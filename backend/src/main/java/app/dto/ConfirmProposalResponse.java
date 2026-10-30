package app.dto;

import app.assistant.ProposedEntry;
import app.assistant.ProposedPrice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ConfirmProposalResponse(
        @Schema(description = "CONFIRMED if saved as proposed, EDITED if the user changed it first", example = "CONFIRMED") String outcome,
        @Schema(description = "Sales proposals: what was saved; previousQuantity shows any quantity that was replaced") List<ProposedEntry> saved,
        @Schema(description = "Price proposals: the price that was saved") ProposedPrice priceChange
) {}
