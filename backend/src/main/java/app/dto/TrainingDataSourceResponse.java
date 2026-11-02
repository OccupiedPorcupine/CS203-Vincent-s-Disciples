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
        Instant uploadedAt,
        LastImport lastImport
) {
    // The latest import into daily sales, or null if the file hasn't been imported.
    public record LastImport(Instant at, int rowsAdded, int rowsOverwritten, int rowsSkipped) {}
}
