package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.ValidationResponse;
import com.csd.salesforecast.domain.*;
import com.csd.salesforecast.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

    @Test
    void recordsSourceTypeAndObjectLocationOnUpload() throws Exception {
        var sources = mock(DataSourceRepository.class);
        var storage = mock(FileStorageService.class);
        var ml = mock(MlClient.class);
        var file = new MockMultipartFile("file", "sales.xlsx", DataSourceService.XLSX_MIME_TYPE, "rows".getBytes());
        var stored = new FileStorageService.StoredFile(Path.of("/data/uploads/sales.xlsx"), "e".repeat(64));
        when(storage.store(file)).thenReturn(stored);
        when(sources.findBySha256(stored.sha256())).thenReturn(Optional.empty());
        when(ml.validate(stored.path())).thenReturn(new ValidationResponse(true, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 90, List.of("Daily Summary"), List.of()));
        when(storage.publish(stored, "sales.xlsx", DataSourceService.XLSX_MIME_TYPE))
            .thenReturn(new FileStorageService.ObjectLocation("raw-sources", "sources/" + stored.sha256() + "/sales.xlsx"));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service(sources, storage, ml).upload(file);

        var captor = ArgumentCaptor.forClass(DataSourceEntity.class);
        verify(sources).save(captor.capture());
        var saved = captor.getValue();
        assertEquals("xlsx", saved.sourceType);
        assertEquals(DataSourceService.XLSX_MIME_TYPE, saved.mimeType);
        assertEquals("demo-backend", saved.ingestedBy);
        assertEquals("raw-sources", saved.storageBucket);
        assertEquals("sources/" + stored.sha256() + "/sales.xlsx", saved.storageKey);
    }

    @Test
    void discardsDuplicateUploadWithoutPublishing() throws Exception {
        var sources = mock(DataSourceRepository.class);
        var storage = mock(FileStorageService.class);
        var ml = mock(MlClient.class);
        var file = new MockMultipartFile("file", "sales.xlsx", DataSourceService.XLSX_MIME_TYPE, "rows".getBytes());
        var stored = new FileStorageService.StoredFile(Path.of("/data/uploads/copy.xlsx"), "f".repeat(64));
        var existing = new DataSourceEntity();
        existing.id = UUID.randomUUID(); existing.originalFileName = "sales.xlsx"; existing.status = "VALIDATED";
        existing.sha256 = stored.sha256(); existing.createdAt = OffsetDateTime.now();
        when(storage.store(file)).thenReturn(stored);
        when(sources.findBySha256(stored.sha256())).thenReturn(Optional.of(existing));

        var response = service(sources, storage, ml).upload(file);

        assertEquals(existing.id, response.id());
        verify(storage).discard(stored);
        verify(storage, never()).publish(any(), any(), any());
        verifyNoInteractions(ml);
    }

    private static DataSourceService service(DataSourceRepository sources, FileStorageService storage, MlClient ml) {
        var models = mock(ModelVersionRepository.class);
        when(models.findFirstByActiveTrueOrderByCreatedAtDesc()).thenReturn(Optional.empty());
        return new DataSourceService(sources, storage, ml, models, mock(TrainingRunSourceRepository.class));
    }
}
