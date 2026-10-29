package app.service;

import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingDataSourceServiceTest {

    @TempDir Path storage;

    @Test
    void storesAndRegistersAValidTrainingSource() throws Exception {
        var sourceRepository = mock(TrainingDataSourceRepository.class);
        var userRepository = mock(AppUserRepository.class);
        var user = mock(AppUser.class);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(sourceRepository.findByOwnerIdAndSha256(eq(7L), anyString())).thenReturn(Optional.empty());
        when(sourceRepository.save(any(TrainingDataSource.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var service = new TrainingDataSourceService(sourceRepository, userRepository, storage.toString());
        var file = new MockMultipartFile("file", "sales.csv", "text/csv", "date,sales\n2026-09-01,42\n".getBytes());

        var response = service.upload(7L, file);

        assertEquals("sales.csv", response.filename());
        assertEquals("AVAILABLE_FOR_TRAINING", response.status());
        assertFalse(response.usedByModel());
        assertEquals(64, response.sha256().length());
        assertEquals(1, FilesHelper.countRegularFiles(storage));
        verify(sourceRepository).save(any(TrainingDataSource.class));
    }

    @Test
    void rejectsUnsupportedFileTypes() throws Exception {
        var service = new TrainingDataSourceService(
                mock(TrainingDataSourceRepository.class), mock(AppUserRepository.class), storage.toString());
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "not training data".getBytes());

        var error = assertThrows(IllegalArgumentException.class, () -> service.upload(7L, file));
        assertEquals("Only CSV and XLSX files are supported", error.getMessage());
    }

    private static final class FilesHelper {
        static long countRegularFiles(Path directory) throws Exception {
            try (var files = java.nio.file.Files.list(directory)) {
                return files.filter(java.nio.file.Files::isRegularFile).count();
            }
        }
    }
}
