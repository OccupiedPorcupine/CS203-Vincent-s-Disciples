package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.TrainingRunResponse;
import com.csd.salesforecast.domain.TrainingRunEntity;
import com.csd.salesforecast.domain.TrainingRunSourceEntity;
import com.csd.salesforecast.repository.*;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class TrainingService {
    private final TrainingRunRepository runs;
    private final DataSourceRepository sources;
    private final TrainingRunSourceRepository lineage;
    private final TrainingWorker worker;
    private final FileStorageService storage;

    public TrainingService(TrainingRunRepository runs, DataSourceRepository sources,
        TrainingRunSourceRepository lineage, TrainingWorker worker, FileStorageService storage) {
        this.runs = runs; this.sources = sources; this.lineage = lineage; this.worker = worker; this.storage = storage;
    }

    public TrainingRunResponse start() {
        var selected = sources.findByIncludedTrueAndStatusOrderByCreatedAtDesc("VALIDATED");
        if (selected.isEmpty()) throw new IllegalStateException("No validated data source is included");
        var resolvedPaths = selected.stream().map(source -> {
            var resolved = storage.resolveStoredPath(source.storedPath);
            if (!resolved.toString().equals(source.storedPath)) {
                source.storedPath = resolved.toString();
                sources.save(source);
            }
            return resolved.toString();
        }).toList();
        TrainingRunEntity run = new TrainingRunEntity();
        run.id = UUID.randomUUID(); run.status = "QUEUED"; run.sourceCount = selected.size();
        run.message = "Waiting to start"; run.startedAt = OffsetDateTime.now();
        runs.save(run);
        for (var source : selected) {
            TrainingRunSourceEntity link = new TrainingRunSourceEntity();
            link.id = UUID.randomUUID(); link.trainingRunId = run.id; link.dataSourceId = source.id;
            link.sourceSha256 = source.sha256; link.createdAt = OffsetDateTime.now(); lineage.save(link);
        }
        worker.execute(run.id, resolvedPaths);
        return toResponse(run);
    }

    public List<TrainingRunResponse> list() { return runs.findTop20ByOrderByStartedAtDesc().stream().map(this::toResponse).toList(); }

    TrainingRunResponse toResponse(TrainingRunEntity run) {
        return new TrainingRunResponse(run.id, run.status, run.selectedModel, run.mae, run.wape, run.sourceCount,
            run.message, run.startedAt, run.finishedAt);
    }
}
