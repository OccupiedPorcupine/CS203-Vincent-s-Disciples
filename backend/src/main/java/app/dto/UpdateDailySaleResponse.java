package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record UpdateDailySaleResponse(
        @Schema(description = "Date of the sales record", example = "2026-10-28") LocalDate date,
        @Schema(description = "Quantity now recorded", example = "45") int quantitySold,
        @Schema(description = "Quantity recorded before this change; null if the day had no record", example = "40") Integer previousQuantity
) {}
