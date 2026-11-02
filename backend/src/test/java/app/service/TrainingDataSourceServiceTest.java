package app.service;

import app.datasource.TrainingDataFile;
import app.datasource.TrainingDataFileRepository;
import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingDataSourceServiceTest {

    @TempDir Path oldStorage;

    private final TrainingDataSourceRepository sourceRepository = mock(TrainingDataSourceRepository.class);
    private final TrainingDataFileRepository fileRepository = mock(TrainingDataFileRepository.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final TrainingDataSourceService service = new TrainingDataSourceService(sourceRepository, fileRepository, userRepository);

    @Test
    void storesTheFileInTheDatabaseAndRegistersIt() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(mock(AppUser.class)));
        when(sourceRepository.findByOwnerIdAndSha256(eq(7L), anyString())).thenReturn(Optional.empty());
        when(sourceRepository.save(any(TrainingDataSource.class))).thenAnswer(invocation -> invocation.getArgument(0));
        byte[] content = "date,sales\n2026-09-01,42\n".getBytes();

        var response = service.upload(7L, new MockMultipartFile("file", "sales.csv", "text/csv", content));

        assertEquals("sales.csv", response.filename());
        assertEquals("AVAILABLE_FOR_TRAINING", response.status());
        assertFalse(response.usedByModel());
        assertEquals(64, response.sha256().length());
        var saved = ArgumentCaptor.forClass(TrainingDataFile.class);
        verify(fileRepository).save(saved.capture());
        assertArrayEquals(content, saved.getValue().getContent());
    }

    @Test
    void rejectsUnsupportedFileTypes() {
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "not training data".getBytes());

        var error = assertThrows(IllegalArgumentException.class, () -> service.upload(7L, file));
        assertEquals("Only CSV and XLSX files are supported", error.getMessage());
    }

    // Uploads from before V25 have a path on disk and no row in training_data_files.
    @Test
    void readsAnOlderUploadFromDiskWhileItIsStillThere() throws Exception {
        Path old = Files.writeString(oldStorage.resolve("sales.csv"), "date,sales\n");
        var source = new TrainingDataSource(mock(AppUser.class), "sales.csv", "text/csv", 11, "a".repeat(64), old.toString());
        when(fileRepository.findById(any())).thenReturn(Optional.empty());

        assertArrayEquals("date,sales\n".getBytes(), service.content(source));

        Files.delete(old);
        var error = assertThrows(IllegalArgumentException.class, () -> service.content(source));
        assertEquals("The uploaded file is no longer on the server. Please upload it again.", error.getMessage());
    }
}
