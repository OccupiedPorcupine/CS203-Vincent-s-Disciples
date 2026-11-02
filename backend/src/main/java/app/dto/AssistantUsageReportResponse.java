package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AssistantUsageReportResponse(
        @Schema(description = "First day included (Singapore time)") LocalDate from,
        @Schema(description = "Last day included: today") LocalDate to,
        @Schema(description = "Estimated cost of the accounts listed", example = "3.20") BigDecimal estimatedCostUsd,
        @Schema(description = "Highest estimated cost first") List<AccountUsageResponse> accounts
) {}
