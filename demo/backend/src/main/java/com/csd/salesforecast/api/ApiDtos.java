package com.csd.salesforecast.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.*;
import java.util.*;

public final class ApiDtos {
    private ApiDtos() {}

    public record SourceResponse(UUID id, String fileName, String status, boolean included, LocalDate dateStart,
        LocalDate dateEnd, int rowCount, List<String> sheets, String validationMessage, OffsetDateTime createdAt) {}

    public record ValidationRequest(String path) {}
    public record ValidationResponse(boolean valid, @JsonProperty("date_start") LocalDate dateStart,
        @JsonProperty("date_end") LocalDate dateEnd, @JsonProperty("row_count") int rowCount,
        List<String> sheets, List<String> errors) {}

    public record TrainingRequest(@JsonProperty("run_id") UUID runId, List<String> paths) {}
    public record TrainingResponse(@JsonProperty("selected_model") String selectedModel, double mae, double wape,
        @JsonProperty("artifact_path") String artifactPath, String message) {}

    public record TrainingRunResponse(UUID id, String status, String selectedModel, Double mae, Double wape,
        int sourceCount, String message, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

    public record ForecastRequest(LocalDate date, String weather, boolean holiday, @JsonProperty("stall_open") boolean stallOpen) {}
    public record ItemForecast(String item, int quantity, double revenue) {}
    public record ForecastResponse(LocalDate date, @JsonProperty("predicted_revenue") double predictedRevenue,
        @JsonProperty("predicted_orders") int predictedOrders, @JsonProperty("lower_bound") double lowerBound,
        @JsonProperty("upper_bound") double upperBound, @JsonProperty("recent_average") double recentAverage,
        @JsonProperty("model_name") String modelName, @JsonProperty("training_cutoff") LocalDate trainingCutoff,
        @JsonProperty("item_forecasts") List<ItemForecast> itemForecasts) {}
}
