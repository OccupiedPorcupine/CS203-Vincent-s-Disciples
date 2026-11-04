package app.closure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HawkerCentreRepository extends JpaRepository<HawkerCentre, Long> {

    List<HawkerCentre> findAllByOrderByNameAsc();

    Optional<HawkerCentre> findByName(String name);

    // When NEA's list was last stored; empty before the first sync.
    @Query("SELECT MAX(c.syncedAt) FROM HawkerCentre c")
    Optional<Instant> lastSyncedAt();
}
