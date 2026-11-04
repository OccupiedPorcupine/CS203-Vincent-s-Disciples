package app.controller;

import app.dto.CloseDateRequest;
import app.dto.ClosuresResponse;
import app.dto.HawkerCentreResponse;
import app.dto.SetHawkerCentreRequest;
import app.dto.SetNeaDaysRequest;
import app.closure.Hours;
import app.dto.SetRestDaysRequest;
import app.dto.SetTradingHoursRequest;
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
import java.util.List;

@Tag(name = "Closures", description = "Days the stall is closed: weekly rest days, one-off closed dates and its hawker centre's NEA closures")
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

    @Operation(summary = "Set trading hours", description = "Sets when the stall opens and closes on an open day. "
            + "A closing time before the opening time means the stall closes after midnight.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hours saved; returns all closures"),
            @ApiResponse(responseCode = "400", description = "A time is missing, or both are the same", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PutMapping("/hours")
    public ClosuresResponse setHours(@AuthenticationPrincipal AuthenticatedUser user,
                                     @Valid @RequestBody SetTradingHoursRequest request) {
        return ClosuresResponse.from(stallClosureService.setHours(user.id(), new Hours(request.opensAt(), request.closesAt())));
    }

    @Operation(summary = "Clear trading hours", description = "Forgets the stall's trading hours.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hours cleared; returns all closures"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @DeleteMapping("/hours")
    public ClosuresResponse clearHours(@AuthenticationPrincipal AuthenticatedUser user) {
        return ClosuresResponse.from(stallClosureService.clearHours(user.id()));
    }

    @Operation(summary = "List hawker centres", description = "NEA's hawker centres, by name, for the owner to pick the stall's.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hawker centres listed"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping("/hawker-centres")
    public List<HawkerCentreResponse> hawkerCentres() {
        return stallClosureService.hawkerCentres().stream().map(HawkerCentreResponse::from).toList();
    }

    @Operation(summary = "Set the stall's hawker centre",
            description = "Links the stall to a hawker centre, so NEA's cleaning and works closures count as closed days. "
                    + "A null id unlinks it; days the owner marked closed themselves are kept either way.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hawker centre saved; returns all closures"),
            @ApiResponse(responseCode = "400", description = "Unknown hawker centre", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PutMapping("/hawker-centre")
    public ClosuresResponse setHawkerCentre(@AuthenticationPrincipal AuthenticatedUser user,
                                            @RequestBody SetHawkerCentreRequest request) {
        return ClosuresResponse.from(stallClosureService.setHawkerCentre(user.id(), request.hawkerCentreId()));
    }

    @Operation(summary = "Open or close NEA's closed days",
            description = "Marks the days in a range that NEA closes the stall's hawker centre as open anyway, or closed again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved; returns all closures"),
            @ApiResponse(responseCode = "400", description = "Invalid range", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @PutMapping("/nea-days")
    public ClosuresResponse setNeaDays(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody SetNeaDaysRequest request) {
        return ClosuresResponse.from(stallClosureService.setNeaDaysOpen(user.id(), request.from(), request.to(), request.open()));
    }
}
