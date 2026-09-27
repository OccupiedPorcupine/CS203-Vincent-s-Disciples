package app.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AddDailySalesRequest(
        @NotEmpty @Valid @Schema(description = "Daily sales records to add") List<SaleInput> sales
) {
    public record SaleInput(
            @NotNull @Schema(description = "Date of the sales record", example = "2026-09-27") LocalDate date,
            @PositiveOrZero @Schema(description = "Quantity of the dish sold", example = "42") int quantitySold
    ) {}
}