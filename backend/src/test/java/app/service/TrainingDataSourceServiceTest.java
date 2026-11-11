package app.service;

import app.datasource.TrainingDataFile;
import app.datasource.TrainingDataFileRepository;
import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.salesimport.SalesImportException;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainingDataSourceServiceTest {

    @TempDir
    Path oldStorage;

    private final TrainingDataSourceRepository sourceRepository =
            mock(TrainingDataSourceRepository.class);

    private final TrainingDataFileRepository fileRepository =
            mock(TrainingDataFileRepository.class);

    private final AppUserRepository userRepository =
            mock(AppUserRepository.class);

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-11-11T00:00:00Z"),
            ZoneOffset.UTC);

    private final TrainingDataSourceService service =
            new TrainingDataSourceService(
                    sourceRepository,
                    fileRepository,
                    userRepository,
                    clock);

    @Test
    void storesTheFileInTheDatabaseAndRegistersIt() {
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(mock(AppUser.class)));

        when(sourceRepository.findByOwnerIdAndSha256(eq(7L), anyString()))
                .thenReturn(Optional.empty());

        when(sourceRepository.save(any(TrainingDataSource.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        byte[] content =
                "date,dish,quantity\n2026-09-01,Rice,42\n".getBytes();

        var response = service.upload(
                7L,
                new MockMultipartFile(
                        "file", "sales.csv", "text/csv", content));

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
        var file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "not training data".getBytes());

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(7L, file));

        assertEquals(
                "Only CSV and XLSX files are supported",
                error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "date,sales\n2026-09-01,42\n",
            "date,dish,quantity\nnot-a-date,Rice,42\n",
            "date,dish,quantity\n2026-09-01,,42\n",
            "date,dish,quantity\n2026-09-01,Rice,-1\n",
            "date,dish,quantity\n2026-09-01,Rice,42\n2026-09-02,Rice,invalid\n",
            "date,dish,quantity\n2026-11-12,Rice,42\n"
    })
    void rejectsInvalidSalesBeforeRegisteringOrCheckingDuplicates(String csv) {
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(mock(AppUser.class)));

        var file = new MockMultipartFile(
                "file", "sales.csv", "text/csv", csv.getBytes());

        var error = assertThrows(
                SalesImportException.class,
                () -> service.upload(7L, file));

        assertEquals("IMPORT_INVALID", error.getCode());
        assertTrue(error.getErrorCount() > 0);
        verifyNoInteractions(sourceRepository, fileRepository);
    }

    @Test
    void rejectsHeaderOnlyFileWithoutSavingAnything() {
        when(userRepository.findById(7L))
                .thenReturn(Optional.of(mock(AppUser.class)));

        var file = new MockMultipartFile(
                "file",
                "sales.csv",
                "text/csv",
                "date,dish,quantity\n".getBytes());

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> service.upload(7L, file));

        assertEquals("The file has no sales rows.", error.getMessage());
        verifyNoInteractions(sourceRepository, fileRepository);
    }

    @Test
    void listsValidSourcesButExcludesPreviouslyRejectedAndMissingUploads()
            throws Exception {
        Path validPath = Files.writeString(
                oldStorage.resolve("valid.csv"),
                "date,dish,quantity\n2026-09-01,Rice,42\n");

        Path invalidPath = Files.writeString(
                oldStorage.resolve("invalid.csv"),
                "date,sales\n2026-09-01,42\n");

        var valid = new TrainingDataSource(
                mock(AppUser.class),
                "valid.csv",
                "text/csv",
                40,
                "a".repeat(64),
                validPath.toString());

        var invalid = new TrainingDataSource(
                mock(AppUser.class),
                "invalid.csv",
                "text/csv",
                40,
                "b".repeat(64),
                invalidPath.toString());

        var missing = new TrainingDataSource(
                mock(AppUser.class),
                "missing.csv",
                "text/csv",
                40,
                "c".repeat(64),
                null);

        when(sourceRepository.findAllByOwnerIdOrderByUploadedAtDesc(7L))
                .thenReturn(List.of(invalid, valid, missing));

        when(fileRepository.findById(any()))
                .thenReturn(Optional.empty());

        var listed = service.list(7L);

        assertEquals(
                List.of("valid.csv"),
                listed.stream().map(source -> source.filename()).toList());

        verify(sourceRepository, never()).delete(any());
    }

    // Uploads from before V25 have a path on disk and no row in
    // training_data_files.
    @Test
    void readsAnOlderUploadFromDiskWhileItIsStillThere() throws Exception {
        Path old = Files.writeString(
                oldStorage.resolve("sales.csv"),
                "date,sales\n");

        var source = new TrainingDataSource(
                mock(AppUser.class),
                "sales.csv",
                "text/csv",
                11,
                "a".repeat(64),
                old.toString());

        when(fileRepository.findById(any()))
                .thenReturn(Optional.empty());

        assertArrayEquals(
                "date,sales\n".getBytes(),
                service.content(source));

        Files.delete(old);

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> service.content(source));

        assertEquals(
                "The uploaded file is no longer on the server. "
                        + "Please upload it again.",
                error.getMessage());
    }
}