package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

public record SetTradingHoursRequest(
        @NotNull @Schema(type = "string", description = "Opening time, 24-hour", example = "10:00") LocalTime opensAt,
        @NotNull @Schema(type = "string", description = "Closing time, 24-hour; before opensAt if the stall closes after midnight", example = "15:00") LocalTime closesAt
) {}
