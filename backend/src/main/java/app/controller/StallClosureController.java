package app.controller;

import app.dto.CloseDateRequest;
import app.dto.ClosuresResponse;
import app.dto.SetRestDaysRequest;
import app.service.StallClosureService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@Tag(name = "Closures", description = "Days the stall is closed: weekly rest days and one-off closed dates")
@RestController
@RequestMapping("/api/closures")
public class StallClosureController {

    private final StallClosureService stallClosureService;

    public StallClosureController(StallClosureService stallClosureService) {
        this.stallClosureService = stallClosureService;
    }

    @Operation(summary = "Get closures", description = "Lists the signed-in user's weekly rest days and one-off closed dates.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Closures listed"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping
    public ClosuresResponse getClosures(@AuthenticationPrincipal AuthenticatedUser user) {
        return ClosuresResponse.from(stallClosureService.getClosures(user.id()));
    }

    @Operation(summary = "Set rest days", description = "Replaces the days of the week the stall is always closed. At least one day must stay open.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rest days saved; returns all closures"),
            @ApiResponse(responseCode = "400", description = "Invalid request, or every day was marked as a rest day", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PutMapping("/rest-days")
    public ClosuresResponse setRestDays(@AuthenticationPrincipal AuthenticatedUser user,
                                        @Valid @RequestBody SetRestDaysRequest request) {
        return ClosuresResponse.from(stallClosureService.setRestDays(user.id(), request.restDays()));
    }

    @Operation(summary = "Mark a day as closed", description = "Marks one day as closed, with an optional reason. Marking it again changes the reason.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Day marked as closed; returns all closures"),
            @ApiResponse(responseCode = "400", description = "Invalid date or reason", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PutMapping("/dates/{date}")
    public ClosuresResponse closeDate(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @Valid @RequestBody(required = false) CloseDateRequest request) {
        return ClosuresResponse.from(stallClosureService.closeDate(user.id(), date, request == null ? null : request.reason()));
    }

    @Operation(summary = "Reopen a day", description = "Removes a one-off closed day. Removing a day that isn't closed does nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Day reopened; returns all closures"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @DeleteMapping("/dates/{date}")
    public ClosuresResponse reopenDate(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ClosuresResponse.from(stallClosureService.reopenDate(user.id(), date));
    }
}
