package app.holiday;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

public interface PublicHolidayRepository extends JpaRepository<PublicHoliday, Long> {
    boolean existsByDate(LocalDate date);
}