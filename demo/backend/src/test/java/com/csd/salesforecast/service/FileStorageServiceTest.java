package com.csd.salesforecast.service;

import com.csd.salesforecast.domain.DataSourceEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageServiceTest {
    @TempDir Path root;

    @Test
    void localBackendKeepsFilesOnDiskOnly() throws Exception {
        var storage = new FileStorageService(root.toString(), new LocalObjectStore());
        var stored = storage.store(workbook("sales.xlsx", "rows"));

        assertNull(storage.publish(stored, "sales.xlsx", "application/xlsx"));
        assertTrue(Files.isRegularFile(stored.path()));
    }

    @Test
    void publishesUnderContentAddressedKey() throws Exception {
        var objects = new InMemoryObjectStore();
        var storage = new FileStorageService(root.toString(), objects);
        var stored = storage.store(workbook("Stall sales (Jan).xlsx", "rows"));

        var location = storage.publish(stored, "Stall sales (Jan).xlsx", "application/xlsx");

        assertEquals("raw-sources", location.bucket());
        assertEquals("sources/" + stored.sha256() + "/Stall_sales__Jan_.xlsx", location.key());
        assertArrayEquals("rows".getBytes(), objects.files.get(location.key()));
    }

    @Test
    void downloadsMissingWorkingCopyFromObjectStorage() throws Exception {
        var objects = new InMemoryObjectStore();
        var storage = new FileStorageService(root.toString(), objects);
        var stored = storage.store(workbook("sales.xlsx", "rows"));
        var location = storage.publish(stored, "sales.xlsx", "application/xlsx");
        var source = source(stored.sha256(), location);
        source.storedPath = stored.path().toString();
        storage.discard(stored);

        Path copy = storage.localCopy(source);

        assertEquals("rows", Files.readString(copy));
        assertTrue(copy.startsWith(root.resolve("uploads").toAbsolutePath().normalize()));
    }

    @Test
    void rejectsDownloadWhoseHashDiffers() throws Exception {
        var objects = new InMemoryObjectStore();
        var storage = new FileStorageService(root.toString(), objects);
        objects.files.put("sources/x/sales.xlsx", "tampered".getBytes());
        var source = source("c".repeat(64), new FileStorageService.ObjectLocation("raw-sources", "sources/x/sales.xlsx"));

        var error = assertThrows(IllegalStateException.class, () -> storage.localCopy(source));
        assertTrue(error.getMessage().contains("hash"));
        assertFalse(Files.exists(root.resolve("uploads").resolve(source.sha256 + "-sales.xlsx")));
    }

    @Test
    void refusesSourceFromAnotherBucket() throws Exception {
        var storage = new FileStorageService(root.toString(), new InMemoryObjectStore());
        var source = source("d".repeat(64), new FileStorageService.ObjectLocation("other-bucket", "sources/x/sales.xlsx"));

        assertThrows(IllegalStateException.class, () -> storage.localCopy(source));
    }

    private static MockMultipartFile workbook(String name, String content) {
        return new MockMultipartFile("file", name, "application/xlsx", content.getBytes());
    }

    private static DataSourceEntity source(String sha256, FileStorageService.ObjectLocation location) {
        var source = new DataSourceEntity();
        source.id = UUID.randomUUID(); source.originalFileName = "sales.xlsx"; source.sha256 = sha256;
        source.storageBucket = location.bucket(); source.storageKey = location.key();
        return source;
    }

    static class InMemoryObjectStore implements ObjectStore {
        final Map<String, byte[]> files = new HashMap<>();
        @Override public String bucket() { return "raw-sources"; }
        @Override public void put(String key, Path file, String contentType) throws java.io.IOException { files.put(key, Files.readAllBytes(file)); }
        @Override public void download(String key, Path target) throws java.io.IOException { Files.write(target, files.get(key)); }
    }
}
