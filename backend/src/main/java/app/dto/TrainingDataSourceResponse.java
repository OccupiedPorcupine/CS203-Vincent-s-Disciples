package app.dto;

import java.time.Instant;

public record TrainingDataSourceResponse(
        Long id,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String status,
        boolean usedByModel,
        Instant uploadedAt
) {}
