package com.csd.salesforecast.service;

import com.csd.salesforecast.domain.ModelVersionEntity;
import com.csd.salesforecast.repository.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class TrainingWorker {
    private final TrainingRunRepository runs;
    private final ModelVersionRepository models;
    private final MlClient mlClient;

    public TrainingWorker(TrainingRunRepository runs, ModelVersionRepository models, MlClient mlClient) {
        this.runs = runs; this.models = models; this.mlClient = mlClient;
    }

    @Async
    @Transactional
    public void execute(UUID runId, List<String> paths) {
        var run = runs.findById(runId).orElseThrow();
        try {
            run.status = "RUNNING"; run.message = "Comparing candidate models"; runs.save(run);
            var result = mlClient.train(runId, paths);
            for (var current : models.findAllByActiveTrue()) { current.active = false; models.save(current); }
            ModelVersionEntity model = new ModelVersionEntity();
            model.id = UUID.randomUUID(); model.name = result.selectedModel(); model.artifactPath = result.artifactPath();
            model.active = true; model.trainingRunId = runId; model.createdAt = OffsetDateTime.now(); models.save(model);
            run.status = "COMPLETED"; run.selectedModel = result.selectedModel(); run.mae = result.mae(); run.wape = result.wape();
            run.message = result.message(); run.finishedAt = OffsetDateTime.now(); runs.save(run);
        } catch (Exception exception) {
            run.status = "FAILED"; run.message = exception.getMessage(); run.finishedAt = OffsetDateTime.now(); runs.save(run);
        }
    }
}
