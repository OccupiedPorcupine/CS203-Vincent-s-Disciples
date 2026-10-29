package app.service;

import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.dto.TrainingDataSourceResponse;
import app.user.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
public class TrainingDataSourceService {

    private static final long MAX_SIZE_BYTES = 25L * 1024 * 1024;
    private final TrainingDataSourceRepository sources;
    private final AppUserRepository users;
    private final Path storageRoot;

    public TrainingDataSourceService(TrainingDataSourceRepository sources, AppUserRepository users,
                                     @Value("${app.training-data.storage-path}") String storagePath) throws IOException {
        this.sources = sources;
        this.users = users;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        Files.createDirectories(storageRoot);
    }

    public List<TrainingDataSourceResponse> list(Long ownerId) {
        return sources.findAllByOwnerIdOrderByUploadedAtDesc(ownerId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TrainingDataSourceResponse upload(Long ownerId, MultipartFile file) {
        validate(file);
        var owner = users.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String original = safeFilename(file.getOriginalFilename());
        Path staged = null;

        try {
            staged = Files.createTempFile(storageRoot, "upload-", ".part");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            }

            String sha256 = sha256(staged);
            var existing = sources.findByOwnerIdAndSha256(ownerId, sha256);
            if (existing.isPresent()) {
                Files.deleteIfExists(staged);
                return toResponse(existing.get());
            }

            Path target = storageRoot.resolve(sha256 + "-" + original).normalize();
            if (!target.startsWith(storageRoot)) throw new IllegalArgumentException("Invalid filename");
            if (Files.exists(target)) Files.deleteIfExists(staged);
            else Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);

            var source = new TrainingDataSource(
                    owner,
                    original,
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    file.getSize(),
                    sha256,
                    target.toString()
            );
            return toResponse(sources.save(source));
        } catch (IOException exception) {
            if (staged != null) {
                try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
            }
            throw new IllegalStateException("The file could not be stored", exception);
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

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private TrainingDataSourceResponse toResponse(TrainingDataSource source) {
        return new TrainingDataSourceResponse(
                source.getId(), source.getOriginalFilename(), source.getContentType(), source.getSizeBytes(),
                source.getSha256(), source.getStatus(), source.isUsedByModel(), source.getUploadedAt()
        );
    }
}
