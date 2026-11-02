package app.config;

import app.service.MlForecastClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

// The "ml" part of GET /api/health (issue #48). Without the ML service forecasts fall back to the
// baseline, so the app still works: it reports DEGRADED, which keeps the overall check at 200
// (see management.endpoint.health.status.* in application.properties).
@Component("ml")
public class MlHealthIndicator implements HealthIndicator {

    static final Status DEGRADED = new Status("DEGRADED", "The ML service isn't answering; forecasts use the baseline");

    private final MlForecastClient mlClient;

    public MlHealthIndicator(MlForecastClient mlClient) {
        this.mlClient = mlClient;
    }

    @Override
    public Health health() {
        return mlClient.isUp() ? Health.up().build() : Health.status(DEGRADED).build();
    }
}
