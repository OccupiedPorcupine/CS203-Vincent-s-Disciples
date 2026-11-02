package app.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

// Leftovers (portions prepared but not sold) and sell-outs between from and to (inclusive).
// Leftovers are valued at the dish's unit cost that day, the money spent on food not sold.
public record WasteResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = "Leftover portions recorded") int leftoverPortions,
        @Schema(description = "Cost of leftovers whose dish has a unit cost, in S$; null if none has") BigDecimal leftoverCost,
        @Schema(description = "Leftover portions of dishes with no unit cost, left out of leftoverCost") int leftoversWithoutCost,
        @Schema(description = "Days a dish sold out, counting each dish separately") int soldOutDays,
        @Schema(description = "Days with a sold-out time or leftovers recorded for any dish") int daysRecorded,
        @Schema(description = "Dishes that had leftovers or sold out, most costly leftovers first") List<DishWaste> dishes
) {
    public record DishWaste(
            Long dishId,
            String name,
            int leftoverPortions,
            @Schema(description = "Null when the dish has no unit cost") BigDecimal leftoverCost,
            int soldOutDays,
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", description = "Earliest time it sold out; null if it didn't", example = "12:40") LocalTime earliestSoldOutAt
    ) {}
}
