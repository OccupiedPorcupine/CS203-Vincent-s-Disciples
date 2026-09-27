package app.holiday;

import app.service.PublicHolidaySyncService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicHolidaySyncScheduler {

    private final PublicHolidaySyncService syncService;

    public PublicHolidaySyncScheduler(PublicHolidaySyncService syncService) {
        this.syncService = syncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        syncService.syncHolidays();
    }

    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Singapore")
    public void syncMonthly() {
        syncService.syncHolidays();
    }
}