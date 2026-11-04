package app.dto;

import java.util.List;

// The ML service's POST /forecast (Chronos-2, issue #106): a dish's recorded daily sales, and the
// public holidays among those days and the days up to the forecast date.
public record MlForecastRequest(
        String forecast_date,
        List<SalesRecord> sales_history,
        List<String> holidays
) {
    public record SalesRecord(
            String date,
            double demand
    ) {}
}
