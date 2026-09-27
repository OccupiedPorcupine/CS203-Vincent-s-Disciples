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

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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