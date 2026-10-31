package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// Revenue for each day from 'from' to 'to', for the dashboard's heatmap.
public record DailyRevenueResponse(
        LocalDate from,
        LocalDate to,
        @Schema(description = "False when no dish has a price, so there is no revenue to show") boolean anyPrices,
        List<Day> days
) {
    public record Day(
            LocalDate date,
            int portions,
            @Schema(description = "Revenue of priced dishes sold that day; null if none were sold") BigDecimal revenue,
            @Schema(description = "A weekly rest day or a day marked closed") boolean closed
    ) {}
}
