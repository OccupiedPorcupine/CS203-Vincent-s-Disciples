package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// Money from sales between from and to (inclusive), in S$. Portions of dishes without a price
// count towards portions but not revenue; they are never valued at $0.
public record RevenueResponse(
        LocalDate from,
        LocalDate to,
        int portions,
        @Schema(description = "Revenue of priced dishes; null if no dish sold in the period has a price") BigDecimal revenue,
        @Schema(description = "Revenue minus cost, for dishes whose cost is known; null if none") BigDecimal grossProfit,
        @Schema(description = "Portions of dishes with no price") int unpricedPortions,
        @Schema(description = "Portions of priced dishes whose unit cost isn't set, left out of gross profit") int portionsWithoutCost,
        @Schema(description = "Every dish, highest gross profit first, then priced dishes, then the rest") List<DishRevenue> dishes
) {
    public record DishRevenue(
            Long dishId,
            String name,
            int portions,
            @Schema(description = "Price on the last day of the period; null if not set") BigDecimal price,
            BigDecimal unitCost,
            @Schema(description = "Price minus unit cost on the last day of the period") BigDecimal marginPerPortion,
            BigDecimal revenue,
            @Schema(description = "Null when a portion's cost isn't known") BigDecimal grossProfit
    ) {}
}
