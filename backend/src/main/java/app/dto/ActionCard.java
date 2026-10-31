package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

// One card in the dashboard's Actions needed panel.
public record ActionCard(
        @Schema(description = "Identifies the card for dismissing it", example = "missing-sales:2026-11-03") String key,
        @Schema(description = "missing-sales, import, prices, holiday or first-sales", example = "missing-sales") String kind,
        @Schema(example = "You haven't logged sales for Mon 3 Nov") String title,
        @Schema(example = "Add them so your charts and forecasts stay accurate.") String detail,
        @Schema(description = "Text for the card's button", example = "Add sales") String actionLabel,
        @Schema(description = "Page the button opens; null when it opens the record-sales form instead") String href,
        @Schema(description = "For missing-sales: the day to record") LocalDate recordDate
) {}
