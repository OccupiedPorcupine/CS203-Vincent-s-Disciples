package app.dto;

import java.time.DayOfWeek;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record SetRestDaysRequest(
        @NotNull @Schema(description = "Days of the week the stall is always closed; empty for none", example = "[\"TUESDAY\"]") List<@NotNull DayOfWeek> restDays
) {}
