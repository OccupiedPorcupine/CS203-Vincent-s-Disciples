package app.holiday;

import app.service.PublicHolidaySyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicHolidaySyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PublicHolidaySyncScheduler.class);

    private final PublicHolidaySyncService syncService;

    public PublicHolidaySyncScheduler(PublicHolidaySyncService syncService) {
        this.syncService = syncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        sync();
    }

    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Singapore")
    public void syncMonthly() {
        sync();
    }

    // A failed sync (offline, data.gov.sg down or rate-limited) must not stop the app:
    // holidays from earlier syncs are already stored in the database.
    void sync() {
        try {
            syncService.syncHolidays();
        } catch (RuntimeException exception) {
            log.warn("Public holiday sync failed; using stored holidays: {}", exception.getMessage());
        }
    }
}