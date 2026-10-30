package app.closure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ClosedDateRepository extends JpaRepository<ClosedDate, Long> {

    List<ClosedDate> findByOwnerIdOrderByClosedDateAsc(Long ownerId);

    Optional<ClosedDate> findByOwnerIdAndClosedDate(Long ownerId, LocalDate closedDate);
}
