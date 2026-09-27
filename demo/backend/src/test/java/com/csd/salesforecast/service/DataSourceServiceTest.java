package com.csd.salesforecast.service;

import com.csd.salesforecast.domain.*;
import com.csd.salesforecast.repository.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceServiceTest {
    @Test
    void reportsUsageOnlyWhenSourceBelongsToActiveModelsTrainingRun() {
        var sources = mock(DataSourceRepository.class);
        var storage = mock(FileStorageService.class);
        var ml = mock(MlClient.class);
        var models = mock(ModelVersionRepository.class);
        var lineage = mock(TrainingRunSourceRepository.class);

        var source = new DataSourceEntity();
        source.id = UUID.randomUUID(); source.originalFileName = "sales.xlsx"; source.status = "VALIDATED";
        source.included = true; source.sha256 = "a".repeat(64); source.rowCount = 90; source.createdAt = OffsetDateTime.now();
        var model = new ModelVersionEntity();
        model.id = UUID.randomUUID(); model.trainingRunId = UUID.randomUUID(); model.name = "Ridge";
        model.active = true; model.createdAt = OffsetDateTime.now();
        var link = new TrainingRunSourceEntity();
        link.trainingRunId = model.trainingRunId; link.dataSourceId = source.id;

        when(sources.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(source));
        when(models.findFirstByActiveTrueOrderByCreatedAtDesc()).thenReturn(Optional.of(model));
        when(lineage.findByTrainingRunId(model.trainingRunId)).thenReturn(List.of(link));

        var response = new DataSourceService(sources, storage, ml, models, lineage).list().getFirst();

        assertTrue(response.usedByActiveModel());
        assertEquals("Ridge", response.activeModelName());
        assertEquals(model.createdAt, response.activeSince());
    }
}
