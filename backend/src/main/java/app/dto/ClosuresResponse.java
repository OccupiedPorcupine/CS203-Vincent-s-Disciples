package app.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import app.service.StallClosureService.StallClosures;
import io.swagger.v3.oas.annotations.media.Schema;

public record ClosuresResponse(
        @Schema(description = "Days of the week the stall is always closed, Monday first", example = "[\"TUESDAY\"]") List<DayOfWeek> restDays,
        @Schema(description = "One-off closed days, oldest first") List<ClosedDay> closedDates
) {
    public record ClosedDay(
            @Schema(description = "The closed day", format = "date") LocalDate date,
            @Schema(description = "Why the stall is closed, if given", example = "Centre cleaning") String reason
    ) {}

    public static ClosuresResponse from(StallClosures closures) {
        return new ClosuresResponse(
                closures.restDays().stream().sorted().toList(),
                closures.closedDates().stream().map(closed -> new ClosedDay(closed.getClosedDate(), closed.getReason())).toList()
        );
    }
}
