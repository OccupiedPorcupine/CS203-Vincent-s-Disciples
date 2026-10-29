package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConfirmProposalRequest(
        @Size(max = 50) @Schema(description = "The entries as the user edited them; omit to save the proposal unchanged") List<ProposalEntryInput> entries
) {}
