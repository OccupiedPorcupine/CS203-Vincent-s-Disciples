package com.csd.salesforecast.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FileStorageService {
    private final Path uploadRoot;

    public FileStorageService(@Value("${app.storage-path}") String storagePath) throws IOException {
        this.uploadRoot = Path.of(storagePath).toAbsolutePath().normalize().resolve("uploads");
        Files.createDirectories(uploadRoot);
    }

    public StoredFile store(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The workbook is empty");
        String original = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        if (!original.toLowerCase().endsWith(".xlsx")) throw new IllegalArgumentException("Only .xlsx workbooks are supported");
        String safeName = original.replaceAll("[^A-Za-z0-9._-]", "_");
        Path target = uploadRoot.resolve(UUID.randomUUID() + "-" + safeName).normalize();
        if (!target.startsWith(uploadRoot)) throw new IllegalArgumentException("Invalid filename");
        try (InputStream input = file.getInputStream()) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
        return new StoredFile(target, sha256(target));
    }

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
}
