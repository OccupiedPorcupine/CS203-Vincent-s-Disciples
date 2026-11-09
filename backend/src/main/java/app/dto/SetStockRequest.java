package app.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SetStockRequest(
        @NotBlank String unit,
        @PositiveOrZero @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        LocalDate expiresOn
) {}

