package app.dto;

import app.sale.DailySale;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record DailySaleResponse(
        @Schema(description = "Date of the sales record", example = "2026-09-27") LocalDate date,
        @Schema(description = "Quantity of the dish sold", example = "42") int quantitySold
) {
    public static DailySaleResponse from(DailySale sale) {
        return new DailySaleResponse(sale.getSaleDate(), sale.getQuantitySold());
    }
}
