package app.dto;

import java.time.LocalDate;

public record ForecastRequest(Long dishId, LocalDate forecastDate) {}