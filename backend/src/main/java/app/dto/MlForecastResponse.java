package app.dto;

import java.util.List;

// quantiles[i] is the demand reached on quantile_levels[i] of days like the forecast date, so the
// 0.5 one is the median; both lists are in ascending order.
public record MlForecastResponse(
        String forecast_date,
        String model,
        List<Double> quantile_levels,
        List<Double> quantiles,
        double median
) {}
