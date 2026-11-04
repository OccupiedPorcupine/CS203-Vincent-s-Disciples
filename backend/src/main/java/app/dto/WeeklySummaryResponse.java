package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

// The week in a few sentences, to read or hear (issue #56). The 7 days end on the latest recorded
// sale, like the dashboard's cards, and every figure is the dashboard's own.
public record WeeklySummaryResponse(
        @Schema(description = "First day of the week; null if no sales are recorded") LocalDate from,
        @Schema(description = "Last day: the latest recorded sale") LocalDate to,
        int portions,
        @Schema(description = "Portions in the 7 days before") int previousPortions,
        @Schema(description = "Change in portions from the week before, rounded; null if that week had none") Integer changePercent,
        @Schema(description = "Revenue of priced dishes in S$; null if none has a price") BigDecimal revenue,
        @Schema(description = "The dish that sold the most portions; null if none sold") DishCount topDish,
        @Schema(description = "False when no leftovers or sell-outs were recorded that week") boolean wasteRecorded,
        int leftoverPortions,
        @Schema(description = "Cost of the leftovers in S$; null if no leftover dish has a unit cost") BigDecimal leftoverCost,
        @Schema(description = "The dish that sold out on the most days; null if none did") DishCount mostSoldOut,
        @Schema(description = "The summary in English and in Chinese, for reading aloud") Text text
) {
    public record DishCount(String name, int count) {}

    public record Text(String en, String zh) {}
}
