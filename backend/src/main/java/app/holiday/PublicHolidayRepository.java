package app.holiday;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.time.LocalDate;
import java.util.Optional;

public interface PublicHolidayRepository extends JpaRepository<PublicHoliday, Long> {
    boolean existsByDate(LocalDate date);
    Optional<PublicHoliday> findByDate(LocalDate date);
}