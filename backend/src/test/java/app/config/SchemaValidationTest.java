package app.config;

import app.service.PublicHolidaySyncService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Starts the app on H2, runs the Flyway migrations, and lets Hibernate validate every entity
// against the resulting tables. Fails if an entity changed without a matching migration.
@SpringBootTest
class SchemaValidationTest {

    // Stubbed so the test does not call data.gov.sg on startup.
    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired
    Flyway flyway;

    // Every migration on the classpath ran. Compares with the newest one found rather than a fixed
    // number, so branches that each add a migration don't all edit (and conflict on) this line.
    @Test
    void migrationsMatchEntities() {
        MigrationVersion newest = Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        assertEquals(newest, flyway.info().current().getVersion());
        assertEquals(0, flyway.info().pending().length);
    }
}
