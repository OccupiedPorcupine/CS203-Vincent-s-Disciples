package com.csd.salesforecast.service;

import com.csd.salesforecast.domain.*;
import com.csd.salesforecast.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingServiceTest {
    @Test
    void snapshotsSourceIdentityBeforeStartingTraining() throws Exception {
        var runs = mock(TrainingRunRepository.class);
        var sources = mock(DataSourceRepository.class);
        var lineage = mock(TrainingRunSourceRepository.class);
        var worker = mock(TrainingWorker.class);
        var storage = mock(FileStorageService.class);
        var source = new DataSourceEntity();
        source.id = UUID.randomUUID(); source.sha256 = "b".repeat(64); source.storedPath = "/data/sales.xlsx";
        when(sources.findByIncludedTrueAndStatusAndSourceTypeOrderByCreatedAtDesc("VALIDATED", "xlsx")).thenReturn(List.of(source));
        when(storage.localCopy(source)).thenReturn(Path.of(source.storedPath));

        var response = new TrainingService(runs, sources, lineage, worker, storage).start();

        var captor = ArgumentCaptor.forClass(TrainingRunSourceEntity.class);
        verify(lineage).save(captor.capture());
        assertEquals(response.id(), captor.getValue().trainingRunId);
        assertEquals(source.id, captor.getValue().dataSourceId);
        assertEquals(source.sha256, captor.getValue().sourceSha256);
        verify(worker).execute(eq(response.id()), eq(List.of(source.storedPath)));
    }
}
