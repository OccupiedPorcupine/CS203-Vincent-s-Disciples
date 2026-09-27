package com.csd.salesforecast.service;

import com.csd.salesforecast.domain.DataSourceEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileStorageService {
    private final Path uploadRoot;
    private final ObjectStore objectStore;

    public FileStorageService(@Value("${app.storage-path}") String storagePath, ObjectStore objectStore) throws IOException {
        this.uploadRoot = Path.of(storagePath).toAbsolutePath().normalize().resolve("uploads");
        this.objectStore = objectStore;
        Files.createDirectories(uploadRoot);
    }

    public StoredFile store(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The workbook is empty");
        String original = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".xlsx")) throw new IllegalArgumentException("Only .xlsx workbooks are supported");
        Path target = insideUploads(UUID.randomUUID() + "-" + safeName(original));
        try (InputStream input = file.getInputStream()) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
        return new StoredFile(target, sha256(target));
    }

    /** Copies the working file to object storage. Returns null when files stay on local disk only. */
    public ObjectLocation publish(StoredFile file, String originalName, String contentType) throws IOException {
        if (objectStore.bucket() == null) return null;
        String key = "sources/" + file.sha256() + "/" + safeName(originalName);
        objectStore.put(key, file.path(), contentType);
        return new ObjectLocation(objectStore.bucket(), key);
    }

    public void discard(StoredFile file) throws IOException { Files.deleteIfExists(file.path()); }

    /** Local copy for the ML service, fetched from object storage when the working file is missing. */
    public Path localCopy(DataSourceEntity source) throws IOException {
        if (source.storedPath != null) {
            try { return resolveStoredPath(source.storedPath); }
            catch (IllegalStateException missing) { if (source.storageKey == null) throw missing; }
        }
        if (source.storageKey == null) throw new IllegalStateException("Source has no stored file: " + source.originalFileName);
        if (!Objects.equals(source.storageBucket, objectStore.bucket()))
            throw new IllegalStateException("Source is stored in bucket " + source.storageBucket + " but storage is configured for " + objectStore.bucket());

        Path target = insideUploads(source.sha256 + "-" + safeName(source.originalFileName));
        if (Files.isRegularFile(target)) return target;
        objectStore.download(source.storageKey, target);
        if (!sha256(target).equals(source.sha256)) {
            Files.deleteIfExists(target);
            throw new IllegalStateException("Downloaded file does not match the recorded hash: " + source.originalFileName);
        }
        return target;
    }

    public Path resolveStoredPath(String storedPath) {
        Path recorded = Path.of(storedPath).toAbsolutePath().normalize();
        if (Files.isRegularFile(recorded)) return recorded;

        Path relocated = uploadRoot.resolve(recorded.getFileName()).normalize();
        if (relocated.startsWith(uploadRoot) && Files.isRegularFile(relocated)) return relocated;

        throw new IllegalStateException("Stored workbook is missing: " + recorded.getFileName());
    }

    private Path insideUploads(String fileName) {
        Path target = uploadRoot.resolve(fileName).normalize();
        if (!target.startsWith(uploadRoot)) throw new IllegalArgumentException("Invalid filename");
        return target;
    }

    private static String safeName(String name) { return name.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) > 0;) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record StoredFile(Path path, String sha256) {}
    public record ObjectLocation(String bucket, String key) {}
}
