package app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DishPriceResponse(Long dishId, LocalDate effectiveFrom, BigDecimal price, BigDecimal unitCost) {}
