package app.closure;

import app.service.HawkerCentreSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

// Keeps NEA's hawker centre closures fresh: daily, and on startup when the stored copy is more than
// a day old. The startup check matters on hosts that scale to zero, where the daily run may never
// fire; it also means most restarts don't call data.gov.sg at all. Tests turn it off
// (app.nea.hawker-centre-sync.enabled=false) so they never call data.gov.sg.
@Component
@ConditionalOnProperty(name = "app.nea.hawker-centre-sync.enabled", havingValue = "true", matchIfMissing = true)
public class HawkerCentreSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(HawkerCentreSyncScheduler.class);
    static final Duration STALE_AFTER = Duration.ofHours(20);

    private final HawkerCentreSyncService syncService;
    private final Clock clock;

    public HawkerCentreSyncScheduler(HawkerCentreSyncService syncService, Clock clock) {
        this.syncService = syncService;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            if (syncService.lastSyncedAt().isAfter(clock.instant().minus(STALE_AFTER))) return;
        } catch (RuntimeException exception) {
            log.warn("Couldn't read when hawker centres were last synced: {}", exception.getMessage());
            return;
        }
        sync();
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Singapore")
    public void syncDaily() {
        sync();
    }

    // A failed sync (offline, data.gov.sg down or rate-limited) must not stop the app: closures
    // from earlier syncs are already stored.
    void sync() {
        try {
            syncService.syncCentres();
        } catch (RuntimeException exception) {
            log.warn("Hawker centre sync failed; using stored closures: {}", exception.getMessage());
        }
    }
}
