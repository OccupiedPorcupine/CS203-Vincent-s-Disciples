package app.holiday;

import app.service.PublicHolidaySyncService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class PublicHolidaySyncSchedulerTest {

    @Test
    void failedSyncDoesNotStopStartup() {
        PublicHolidaySyncService syncService = mock(PublicHolidaySyncService.class);
        doThrow(new IllegalStateException("Public holiday data unavailable")).when(syncService).syncHolidays();

        assertDoesNotThrow(() -> new PublicHolidaySyncScheduler(syncService).syncOnStartup());
        verify(syncService).syncHolidays();
    }
}
