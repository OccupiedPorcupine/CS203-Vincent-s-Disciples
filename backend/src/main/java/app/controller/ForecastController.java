package app.controller;

import app.dish.Dish;
import app.dto.ForecastRequest;
import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import app.service.ForecastDataService;
import app.service.MlForecastClient;
import app.user.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final MlForecastClient mlForecastClient;
    private final ForecastDataService forecastDataService;

    public ForecastController(MlForecastClient mlForecastClient, ForecastDataService forecastDataService) {
        this.mlForecastClient = mlForecastClient;
        this.forecastDataService = forecastDataService;
    }

    @PostMapping
    public MlPredictionResponse predict(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ForecastRequest request
    ) {

        Dish dish = forecastDataService.getOwnedDish(
                request.dishId(),
                user.id()
        );

        var salesHistory = forecastDataService.getSalesHistory(
                dish.getId(),
                request.forecastDate()
        );

        var weather =
                new MlPredictionRequest.WeatherInput(
                        request.weather().wind(),
                        request.weather().cloudCover(),
                        request.weather().precipitation(),
                        request.weather().sunshine(),
                        request.weather().airTemperature()
                );

        MlPredictionRequest mlRequest =
                new MlPredictionRequest(
                        dish.getName(),
                        request.forecastDate().toString(),
                        salesHistory,
                        weather,
                        request.isHoliday()
                );

        return mlForecastClient.predict(mlRequest);
    }
}