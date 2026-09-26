package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.*;
import com.csd.salesforecast.domain.ForecastEntity;
import com.csd.salesforecast.repository.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.UUID;

@Service
public class ForecastService {
    private final MlClient mlClient;
    private final ForecastRepository forecasts;
    private final ModelVersionRepository models;

    public ForecastService(MlClient mlClient, ForecastRepository forecasts, ModelVersionRepository models) {
        this.mlClient = mlClient; this.forecasts = forecasts; this.models = models;
    }

    public ForecastResponse tomorrow(String weather, boolean holiday, boolean stallOpen) {
        LocalDate target = LocalDate.now(ZoneId.of("Asia/Singapore")).plusDays(1);
        ForecastResponse response = mlClient.forecast(new ForecastRequest(target, weather, holiday, stallOpen));
        ForecastEntity entity = new ForecastEntity();
        entity.id = UUID.randomUUID(); entity.targetDate = target; entity.weather = weather; entity.holiday = holiday; entity.stallOpen = stallOpen;
        entity.predictedRevenue = response.predictedRevenue(); entity.predictedOrders = response.predictedOrders(); entity.lowerBound = response.lowerBound(); entity.upperBound = response.upperBound();
        models.findFirstByActiveTrueOrderByCreatedAtDesc().ifPresent(model -> entity.modelVersionId = model.id);
        entity.createdAt = OffsetDateTime.now(); forecasts.save(entity);
        return response;
    }
}
