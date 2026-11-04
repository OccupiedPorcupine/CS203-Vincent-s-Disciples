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
        @Schema(description = "One-off closed days, oldest first: the owner's own and NEA's closures of the stall's hawker centre") List<ClosedDay> closedDates,
        @Schema(description = "When the stall trades on an open day; null if not set") TradingHours hours,
        @Schema(description = "The stall's hawker centre; null if none is picked") HawkerCentre hawkerCentre,
        @Schema(description = "NEA's closures of the stall's hawker centre that haven't ended, soonest first") List<NeaClosure> neaClosures
) {
    public record TradingHours(
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", example = "10:00") LocalTime opensAt,
            @JsonFormat(pattern = "HH:mm") @Schema(type = "string", description = "Before opensAt when the stall closes after midnight", example = "15:00") LocalTime closesAt
    ) {}

    public record ClosedDay(
            @Schema(description = "The closed day", format = "date") LocalDate date,
            @Schema(description = "Why the stall is closed, if given", example = "Centre cleaning") String reason,
            @Schema(description = "OWNER for a day the owner marked closed; NEA for a day NEA closes the hawker centre", example = "OWNER") String source
    ) {}

    public record HawkerCentre(Long id, @Schema(example = "Adam Road Food Centre") String name) {}

    public record NeaClosure(
            @Schema(format = "date") LocalDate startDate,
            @Schema(format = "date") LocalDate endDate,
            @Schema(example = "Centre cleaning (NEA)") String reason,
            @Schema(description = "NEA's note, if any", example = "Only Cooked Food Section is closed") String remarks,
            @Schema(description = "Days in the closure the stall opens anyway") List<LocalDate> openDays
    ) {}

    public static ClosuresResponse from(StallClosures closures) {
        return new ClosuresResponse(
                closures.restDays().stream().sorted().toList(),
                closures.closedDates().stream()
                        .map(closed -> new ClosedDay(closed.date(), closed.reason(), closed.fromNea() ? "NEA" : "OWNER"))
                        .toList(),
                closures.hours() == null ? null : new TradingHours(closures.hours().opensAt(), closures.hours().closesAt()),
                closures.hawkerCentre() == null ? null : new HawkerCentre(closures.hawkerCentre().id(), closures.hawkerCentre().name()),
                closures.neaClosures().stream()
                        .map(nea -> new NeaClosure(nea.startDate(), nea.endDate(), nea.reason(), nea.remarks(), nea.openDays()))
                        .toList()
        );
    }
}
