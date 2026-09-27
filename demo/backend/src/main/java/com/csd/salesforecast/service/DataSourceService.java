package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.*;
import com.csd.salesforecast.domain.DataSourceEntity;
import com.csd.salesforecast.domain.ModelVersionEntity;
import com.csd.salesforecast.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DataSourceService {
    static final String XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DataSourceRepository repository;
    private final FileStorageService storage;
    private final MlClient mlClient;
    private final ModelVersionRepository models;
    private final TrainingRunSourceRepository lineage;

    public DataSourceService(DataSourceRepository repository, FileStorageService storage, MlClient mlClient,
        ModelVersionRepository models, TrainingRunSourceRepository lineage) {
        this.repository = repository; this.storage = storage; this.mlClient = mlClient;
        this.models = models; this.lineage = lineage;
    }

    public SourceResponse upload(MultipartFile file) throws Exception {
        var stored = storage.store(file);
        var existing = repository.findBySha256(stored.sha256());
        if (existing.isPresent()) {
            storage.discard(stored);
            return toResponse(existing.get(), activeModel(), activeSourceIds());
        }

        ValidationResponse validation = mlClient.validate(stored.path());
        DataSourceEntity source = new DataSourceEntity();
        source.id = UUID.randomUUID();
        source.originalFileName = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        source.storedPath = stored.path().toString();
        source.sha256 = stored.sha256();
        source.sourceType = "xlsx";
        source.mimeType = XLSX_MIME_TYPE;
        source.ingestedBy = "demo-backend";
        var published = storage.publish(stored, source.originalFileName, XLSX_MIME_TYPE);
        if (published != null) { source.storageBucket = published.bucket(); source.storageKey = published.key(); }
        source.status = validation.valid() ? "VALIDATED" : "REJECTED";
        source.included = validation.valid();
        source.dateStart = validation.dateStart();
        source.dateEnd = validation.dateEnd();
        source.rowCount = validation.rowCount();
        source.sheetNames = String.join("|", validation.sheets());
        source.validationMessage = validation.errors().isEmpty() ? "Workbook structure validated" : String.join("; ", validation.errors());
        source.createdAt = OffsetDateTime.now();
        return toResponse(repository.save(source), activeModel(), activeSourceIds());
    }

    public List<SourceResponse> list() {
        var active = activeModel();
        var activeSourceIds = active.map(model -> lineage.findByTrainingRunId(model.trainingRunId).stream()
            .map(link -> link.dataSourceId).collect(java.util.stream.Collectors.toSet())).orElseGet(Set::of);
        return repository.findAllByOrderByCreatedAtDesc().stream()
            .map(source -> toResponse(source, active, activeSourceIds)).toList();
    }

    private Optional<ModelVersionEntity> activeModel() { return models.findFirstByActiveTrueOrderByCreatedAtDesc(); }

    private Set<UUID> activeSourceIds() {
        return activeModel().map(model -> lineage.findByTrainingRunId(model.trainingRunId).stream()
            .map(link -> link.dataSourceId).collect(java.util.stream.Collectors.toSet())).orElseGet(Set::of);
    }

    private SourceResponse toResponse(DataSourceEntity source, Optional<ModelVersionEntity> active, Set<UUID> activeSourceIds) {
        List<String> sheets = source.sheetNames == null || source.sheetNames.isBlank() ? List.of() : Arrays.asList(source.sheetNames.split("\\|"));
        boolean usedByActiveModel = activeSourceIds.contains(source.id);
        return new SourceResponse(source.id, source.originalFileName, source.status, source.included, source.dateStart,
            source.dateEnd, source.rowCount, sheets, source.validationMessage, source.createdAt, usedByActiveModel,
            usedByActiveModel ? active.map(model -> model.name).orElse(null) : null,
            usedByActiveModel ? active.map(model -> model.createdAt).orElse(null) : null);
    }
}
