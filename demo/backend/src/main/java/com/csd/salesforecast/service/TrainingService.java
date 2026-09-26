package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.TrainingRunResponse;
import com.csd.salesforecast.domain.TrainingRunEntity;
import com.csd.salesforecast.repository.*;
import org.springframework.stereotype.Service;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class TrainingService {
    private final TrainingRunRepository runs;
    private final DataSourceRepository sources;
    private final TrainingWorker worker;

    public TrainingService(TrainingRunRepository runs, DataSourceRepository sources, TrainingWorker worker) {
        this.runs = runs; this.sources = sources; this.worker = worker;
    }

    public TrainingRunResponse start() {
        var selected = sources.findByIncludedTrueAndStatusOrderByCreatedAtDesc("VALIDATED");
        if (selected.isEmpty()) throw new IllegalStateException("No validated data source is included");
        TrainingRunEntity run = new TrainingRunEntity();
        run.id = UUID.randomUUID(); run.status = "QUEUED"; run.sourceCount = selected.size();
        run.message = "Waiting to start"; run.startedAt = OffsetDateTime.now();
        runs.save(run);
        worker.execute(run.id, selected.stream().map(source -> source.storedPath).toList());
        return toResponse(run);
    }

    public List<TrainingRunResponse> list() { return runs.findTop20ByOrderByStartedAtDesc().stream().map(this::toResponse).toList(); }

    TrainingRunResponse toResponse(TrainingRunEntity run) {
        return new TrainingRunResponse(run.id, run.status, run.selectedModel, run.mae, run.wape, run.sourceCount,
            run.message, run.startedAt, run.finishedAt);
    }
}
