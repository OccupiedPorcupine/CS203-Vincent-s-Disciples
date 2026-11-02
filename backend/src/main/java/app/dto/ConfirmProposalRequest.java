package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record ConfirmProposalRequest(
        @Size(max = 50) @Schema(description = "Sales proposals: the entries as the user edited them; omit to save the proposal unchanged") List<ProposalEntryInput> entries,
        @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 2)
        @Schema(description = "Price proposals: the price as the user edited it; omit to save the proposed price") BigDecimal price,
        @DecimalMin("0") @DecimalMax("10000") @Digits(integer = 5, fraction = 2)
        @Schema(description = "Price proposals: the unit cost as the user edited it; omit to keep the proposed or current cost") BigDecimal unitCost
) {}
