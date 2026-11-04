package app.closure;

import app.service.HawkerCentreSyncService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HawkerCentreSyncSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Singapore"));

    @Test
    void startupSkipsTheSyncWhenTheStoredCopyIsRecent() {
        HawkerCentreSyncService service = mock(HawkerCentreSyncService.class);
        when(service.lastSyncedAt()).thenReturn(NOW.minus(Duration.ofHours(3)));

        new HawkerCentreSyncScheduler(service, CLOCK).syncOnStartup();

        verify(service, never()).syncCentres();
    }

    @Test
    void startupSyncsWhenTheStoredCopyIsADayOld() {
        HawkerCentreSyncService service = mock(HawkerCentreSyncService.class);
        when(service.lastSyncedAt()).thenReturn(NOW.minus(Duration.ofHours(25)));

        new HawkerCentreSyncScheduler(service, CLOCK).syncOnStartup();

        verify(service).syncCentres();
    }

    @Test
    void aFailedSyncDoesNotStopTheApp() {
        HawkerCentreSyncService service = mock(HawkerCentreSyncService.class);
        when(service.lastSyncedAt()).thenReturn(Instant.EPOCH);
        doThrow(new IllegalStateException("data.gov.sg is down")).when(service).syncCentres();

        assertDoesNotThrow(() -> new HawkerCentreSyncScheduler(service, CLOCK).syncOnStartup());
        assertDoesNotThrow(() -> new HawkerCentreSyncScheduler(service, CLOCK).syncDaily());
    }
}
