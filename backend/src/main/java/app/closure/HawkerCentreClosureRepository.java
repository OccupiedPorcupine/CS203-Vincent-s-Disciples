package app.closure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HawkerCentreClosureRepository extends JpaRepository<HawkerCentreClosure, Long> {

    List<HawkerCentreClosure> findByCentreId(Long centreId);

    // Closures that overlap the days from..to.
    List<HawkerCentreClosure> findByCentreIdAndEndDateGreaterThanEqualAndStartDateLessThanEqualOrderByStartDateAsc(
            Long centreId, LocalDate from, LocalDate to);
}
