package app.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalTime;

public record UpdateDailySaleResponse(
        @Schema(description = "Date of the sales record", example = "2026-10-28") LocalDate date,
        @Schema(description = "Quantity now recorded", example = "45") int quantitySold,
        @Schema(description = "Quantity recorded before this change; null if the day had no record", example = "40") Integer previousQuantity,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", description = "When the dish sold out; null if it didn't", example = "12:40") LocalTime soldOutAt,
        @Schema(description = "Portions left over; null if not recorded", example = "3") Integer leftoverQuantity
) {}
