package app.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AddDailySalesRequest(
        @NotEmpty @Size(max = AddDailySalesRequest.MAX_SALES) @Valid @Schema(description = "Daily sales records to add") List<SaleInput> sales
) {
    // Upper bounds, so one request can't insert an unbounded number of rows or absurd values.
    // MAX_QUANTITY matches ProposalValidator.MAX_QUANTITY for sales entered through the assistant.
    public static final int MAX_SALES = 1000;
    public static final int MAX_QUANTITY = 9999;

    public record SaleInput(
            @NotNull @Schema(description = "Date of the sales record", example = "2026-09-27") LocalDate date,
            @PositiveOrZero @Max(MAX_QUANTITY) @Schema(description = "Quantity of the dish sold", example = "42") int quantitySold,
            @Schema(type = "string", description = "When the dish sold out (optional)", example = "12:40") LocalTime soldOutAt,
            @PositiveOrZero @Max(MAX_QUANTITY) @Schema(description = "Portions left over (optional)", example = "3") Integer leftoverQuantity
    ) {
        public SaleInput(LocalDate date, int quantitySold) {
            this(date, quantitySold, null, null);
        }
    }
}