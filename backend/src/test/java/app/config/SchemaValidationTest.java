package app.config;

import app.service.PublicHolidaySyncService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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

    @Test
    void migrationsMatchEntities() {
        assertEquals("1", flyway.info().current().getVersion().getVersion());
    }
}
