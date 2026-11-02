package app.dto;

import app.sale.DailySale;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalTime;

public record DailySaleResponse(
        @Schema(description = "Date of the sales record", example = "2026-09-27") LocalDate date,
        @Schema(description = "Quantity of the dish sold", example = "42") int quantitySold,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", description = "When the dish sold out; null if it didn't or it wasn't recorded", example = "12:40") LocalTime soldOutAt,
        @Schema(description = "Portions left over; null if not recorded", example = "3") Integer leftoverQuantity
) {
    public static DailySaleResponse from(DailySale sale) {
        return new DailySaleResponse(sale.getSaleDate(), sale.getQuantitySold(), sale.getSoldOutAt(), sale.getLeftoverQuantity());
    }
}
