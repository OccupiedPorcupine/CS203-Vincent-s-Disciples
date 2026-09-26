package app.dto;

import java.util.List;

public record MlPredictionRequest(
        String dish,
        String forecast_date,
        List<SalesRecord> sales_history,
        WeatherInput weather,
        boolean is_holiday
) {
    public record SalesRecord(
            String date,
            double demand
    ) {}

    public record WeatherInput(
            double wind,
            double cloud_cover,
            double precipitation,
            double sunshine,
            double air_temperature
    ) {}
}