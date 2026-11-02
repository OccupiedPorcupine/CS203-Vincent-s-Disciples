package app.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import app.service.StallClosureService.StallClosures;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

public record ClosuresResponse(
        @Schema(description = "Days of the week the stall is always closed, Monday first", example = "[\"TUESDAY\"]") List<DayOfWeek> restDays,
        @Schema(description = "One-off closed days, oldest first") List<ClosedDay> closedDates,
        @Schema(description = "When the stall trades on an open day; null if not set") TradingHours hours
) {
    public record TradingHours(
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "10:00") LocalTime opensAt,
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", description = "Before opensAt when the stall closes after midnight", example = "15:00") LocalTime closesAt
    ) {}

    public record ClosedDay(
            @Schema(description = "The closed day", format = "date") LocalDate date,
            @Schema(description = "Why the stall is closed, if given", example = "Centre cleaning") String reason
    ) {}

    public static ClosuresResponse from(StallClosures closures) {
        return new ClosuresResponse(
                closures.restDays().stream().sorted().toList(),
                closures.closedDates().stream().map(closed -> new ClosedDay(closed.getClosedDate(), closed.getReason())).toList(),
                closures.hours() == null ? null : new TradingHours(closures.hours().opensAt(), closures.hours().closesAt())
        );
    }
}
