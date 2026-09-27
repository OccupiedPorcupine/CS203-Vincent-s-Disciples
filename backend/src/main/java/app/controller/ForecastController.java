package app.controller;

import app.dish.Dish;
import app.dto.ForecastRequest;
import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import app.service.ForecastDataService;
import app.service.MlForecastClient;
import app.service.NeaWeatherForecastService;
import app.service.PublicHolidayService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Forecast", description = "Demand forecasting operations")
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

        private final MlForecastClient mlForecastClient;
        private final ForecastDataService forecastDataService;
        private final NeaWeatherForecastService neaWeatherForecastService;
        private final PublicHolidayService publicHolidayService;

        public ForecastController(MlForecastClient mlForecastClient, ForecastDataService forecastDataService, NeaWeatherForecastService neaWeatherForecastService, PublicHolidayService publicHolidayService) {
            this.mlForecastClient = mlForecastClient;
            this.forecastDataService = forecastDataService;
            this.neaWeatherForecastService = neaWeatherForecastService;
            this.publicHolidayService = publicHolidayService;
        }

        @Operation(summary = "Generate demand forecast", description = "Predicts demand for a user's dish on a specified date.")
        @ApiResponses({
                @ApiResponse(responseCode = "200", description = "Forecast generated successfully"),
                @ApiResponse(responseCode = "400", description = "Invalid forecast request", content = @Content),
                @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content),
                @ApiResponse(responseCode = "404", description = "Dish not found", content = @Content),
                @ApiResponse(responseCode = "502", description = "ML forecasting service failed", content = @Content)
        })
        @PostMapping
        public MlPredictionResponse predict(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody ForecastRequest request) {
                Dish dish = forecastDataService.getOwnedDish(request.dishId(), user.id());
                
                var salesHistory = forecastDataService.getSalesHistory(dish.getId(), request.forecastDate());
                
                var weather = neaWeatherForecastService.getWeather(request.forecastDate());
                
                boolean isHoliday = publicHolidayService.isPublicHoliday(request.forecastDate());
                
                MlPredictionRequest mlRequest = new MlPredictionRequest(
                        dish.getName(),
                        request.forecastDate().toString(),
                        salesHistory,
                        weather,
                        isHoliday
                );
        
                return mlForecastClient.predict(mlRequest);
        }

}