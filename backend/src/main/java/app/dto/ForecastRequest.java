package app.dto;

import java.time.LocalDate;

public record ForecastRequest(
        Long dishId,
        LocalDate forecastDate,
        WeatherInput weather,
        boolean isHoliday
) {
    public record WeatherInput(
            double wind,
            double cloudCover,
            double precipitation,
            double sunshine,
            double airTemperature
    ) {}
}