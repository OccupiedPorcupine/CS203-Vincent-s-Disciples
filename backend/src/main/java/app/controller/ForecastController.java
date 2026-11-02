package app.controller;

import app.dto.ForecastRequest;
import app.dto.ForecastResponse;
import app.service.ForecastService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Forecast", description = "Demand forecasting operations")
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

        private final ForecastService forecastService;

        public ForecastController(ForecastService forecastService) {
            this.forecastService = forecastService;
        }

        @Operation(summary = "Generate demand forecast", description = "Predicts demand for a user's dish on a date from today up to 7 days ahead. "
                + "Uses the trained model when it can, and otherwise an average of the dish's own past sales; the response says which, and why.")
        @ApiResponses({
                @ApiResponse(responseCode = "200", description = "Forecast generated successfully"),
                @ApiResponse(responseCode = "400", description = "Invalid request: date out of range, the stall is closed that day, unknown dish, or no sales recorded for the dish", content = @Content),
                @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
        })
        @PostMapping
        public ForecastResponse predict(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody ForecastRequest request) {
                return forecastService.forecast(request.dishId(), request.forecastDate(), user.id());
        }

}
