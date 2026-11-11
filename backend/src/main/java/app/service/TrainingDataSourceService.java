package app.service;

import app.datasource.TrainingDataFile;
import app.datasource.TrainingDataFileRepository;
import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.dto.TrainingDataSourceResponse;
import app.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import app.salesimport.SalesFileReader;
import app.salesimport.SalesImportException;
import java.time.Clock;
import java.time.LocalDate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

// Uploaded sales files. Each file is kept in the database (TrainingDataFile), not on the server's
// disk, which is wiped on every deploy and isn't shared between instances (issue #48).
@Service
public class TrainingDataSourceService {

    private static final long MAX_SIZE_BYTES = 25L * 1024 * 1024;
    private final TrainingDataSourceRepository sources;
    private final TrainingDataFileRepository files;
    private final AppUserRepository users;
    private final Clock clock;

    public TrainingDataSourceService(
            TrainingDataSourceRepository sources,
            TrainingDataFileRepository files,
            AppUserRepository users,
            Clock clock) {
        this.sources = sources;
        this.files = files;
        this.users = users;
        this.clock = clock;
    }

    public List<TrainingDataSourceResponse> list(Long ownerId) {
        return sources.findAllByOwnerIdOrderByUploadedAtDesc(ownerId).stream()
                .filter(this::hasValidSales)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TrainingDataSourceResponse upload(Long ownerId, MultipartFile file) {
        validate(file);
        var owner = users.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String original = safeFilename(file.getOriginalFilename());

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("The file could not be stored", exception);
        }

        validateSales(content, original);
        String sha256 = sha256(content);
        var existing = sources.findByOwnerIdAndSha256(ownerId, sha256);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        var source = sources.save(new TrainingDataSource(
                owner,
                original,
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                file.getSize(),
                sha256,
                null
        ));
        files.save(new TrainingDataFile(source.getId(), content));
        return toResponse(source);
    }

    // The uploaded file's bytes. Uploads from before files were kept in the database are read from
    // their path on disk, if it's still there.
    public byte[] content(TrainingDataSource source) {
        var stored = files.findById(source.getId());
        if (stored.isPresent()) {
            return stored.get().getContent();
        }
        Path path = source.getStoredPath() == null ? null : Path.of(source.getStoredPath());
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("The uploaded file is no longer on the server. Please upload it again.");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException("The uploaded file could not be read", exception);
        }
    }

    // Older uploads were registered before import validation. Keep those records,
    // but do not advertise invalid or missing files as usable training sources.
    private boolean hasValidSales(TrainingDataSource source) {
        try {
            validateSales(content(source), source.getOriginalFilename());
            return true;
        } catch (IllegalArgumentException | SalesImportException exception) {
            return false;
        }
    }

    private void validateSales(byte[] content, String filename) {
        SalesFileReader.SalesFile sales;

        try {
            sales = SalesFileReader.read(
                    content,
                    filename,
                    LocalDate.now(clock));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "The uploaded file could not be read. "
                            + "Save it as CSV or XLSX and try again.",
                    exception);
        }

        if (sales.errorCount() > 0) {
            var first = sales.errors().getFirst();
            String detail = (first.row() == null
                    ? ""
                    : "Row " + first.row() + ": ") + first.message();

            throw new SalesImportException(
                    "IMPORT_INVALID",
                    detail + " Fix the file and upload it again.",
                    sales.errorCount(),
                    sales.errors());
        }

        if (sales.rows().isEmpty()) {
            throw new IllegalArgumentException("The file has no sales rows.");
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a non-empty file");
        if (file.getSize() > MAX_SIZE_BYTES) throw new IllegalArgumentException("The file exceeds 25 MB");
        String name = safeFilename(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        if (!name.endsWith(".csv") && !name.endsWith(".xlsx")) {
            throw new IllegalArgumentException("Only CSV and XLSX files are supported");
        }
    }

    private String safeFilename(String original) {
        String value = original == null || original.isBlank() ? "training-data" : original;
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.isBlank() || value.equals(".") || value.equals("..")) throw new IllegalArgumentException("Invalid filename");
        return value;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public TrainingDataSourceResponse toResponse(TrainingDataSource source) {
        return new TrainingDataSourceResponse(
                source.getId(), source.getOriginalFilename(), source.getContentType(), source.getSizeBytes(),
                source.getSha256(), source.getStatus(), source.isUsedByModel(), source.getUploadedAt(),
                source.getImportedAt() == null ? null : new TrainingDataSourceResponse.LastImport(
                        source.getImportedAt(), source.getImportRowsAdded(),
                        source.getImportRowsOverwritten(), source.getImportRowsSkipped())
        );
    }
}
