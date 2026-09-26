package com.csd.salesforecast.service;

import com.csd.salesforecast.api.ApiDtos.*;
import com.csd.salesforecast.domain.DataSourceEntity;
import com.csd.salesforecast.repository.DataSourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DataSourceService {
    private final DataSourceRepository repository;
    private final FileStorageService storage;
    private final MlClient mlClient;

    public DataSourceService(DataSourceRepository repository, FileStorageService storage, MlClient mlClient) {
        this.repository = repository; this.storage = storage; this.mlClient = mlClient;
    }

    public SourceResponse upload(MultipartFile file) throws Exception {
        var stored = storage.store(file);
        var existing = repository.findBySha256(stored.sha256());
        if (existing.isPresent()) return toResponse(existing.get());

        ValidationResponse validation = mlClient.validate(stored.path());
        DataSourceEntity source = new DataSourceEntity();
        source.id = UUID.randomUUID();
        source.originalFileName = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        source.storedPath = stored.path().toString();
        source.sha256 = stored.sha256();
        source.status = validation.valid() ? "VALIDATED" : "REJECTED";
        source.included = validation.valid();
        source.dateStart = validation.dateStart();
        source.dateEnd = validation.dateEnd();
        source.rowCount = validation.rowCount();
        source.sheetNames = String.join("|", validation.sheets());
        source.validationMessage = validation.errors().isEmpty() ? "Workbook structure validated" : String.join("; ", validation.errors());
        source.createdAt = OffsetDateTime.now();
        return toResponse(repository.save(source));
    }

    public List<SourceResponse> list() { return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toResponse).toList(); }

    private SourceResponse toResponse(DataSourceEntity source) {
        List<String> sheets = source.sheetNames == null || source.sheetNames.isBlank() ? List.of() : Arrays.asList(source.sheetNames.split("\\|"));
        return new SourceResponse(source.id, source.originalFileName, source.status, source.included, source.dateStart,
            source.dateEnd, source.rowCount, sheets, source.validationMessage, source.createdAt);
    }
}
