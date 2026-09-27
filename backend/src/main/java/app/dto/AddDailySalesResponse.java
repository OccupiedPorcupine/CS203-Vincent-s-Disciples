package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AddDailySalesResponse(
        @Schema(description = "Number of sales records successfully added", example = "7") int added
) {}