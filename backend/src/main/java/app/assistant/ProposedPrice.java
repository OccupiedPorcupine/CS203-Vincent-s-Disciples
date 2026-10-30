package app.assistant;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

// A price change the assistant understood, shown on a confirm card. Nothing changes until the
// user confirms. unitCost null means keep the current cost.
public record ProposedPrice(
        Long dishId,
        String dishName,
        @Schema(description = "New price per portion in S$", example = "5.00") BigDecimal price,
        @Schema(description = "New unit cost in S$; null keeps the current cost") BigDecimal unitCost,
        @Schema(description = "First day the new price applies") LocalDate effectiveFrom,
        @Schema(description = "Price before the change; null if the dish had none") BigDecimal currentPrice,
        BigDecimal currentUnitCost
) {}
