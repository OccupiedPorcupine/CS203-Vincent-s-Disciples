package app.closure;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StallHawkerCentreRepository extends JpaRepository<StallHawkerCentre, Long> {

    @EntityGraph(attributePaths = "centre")
    Optional<StallHawkerCentre> findByOwnerId(Long ownerId);
}
