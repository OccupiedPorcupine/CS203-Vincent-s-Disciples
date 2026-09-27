package app.holiday;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class PublicHolidaySeeder implements CommandLineRunner {

    private final PublicHolidayRepository repository;

    public PublicHolidaySeeder(PublicHolidayRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        List<PublicHoliday> holidays = List.of(
                new PublicHoliday(LocalDate.of(2026, 1, 1), "New Year's Day"),
                new PublicHoliday(LocalDate.of(2026, 2, 17), "Chinese New Year"),
                new PublicHoliday(LocalDate.of(2026, 2, 18), "Chinese New Year"),
                new PublicHoliday(LocalDate.of(2026, 3, 21), "Hari Raya Puasa"),
                new PublicHoliday(LocalDate.of(2026, 4, 3), "Good Friday"),
                new PublicHoliday(LocalDate.of(2026, 5, 1), "Labour Day"),
                new PublicHoliday(LocalDate.of(2026, 5, 27), "Hari Raya Haji"),
                new PublicHoliday(LocalDate.of(2026, 5, 31), "Vesak Day"),
                new PublicHoliday(LocalDate.of(2026, 6, 1), "Vesak Day (Observed)"),
                new PublicHoliday(LocalDate.of(2026, 8, 9), "National Day"),
                new PublicHoliday(LocalDate.of(2026, 8, 10), "National Day (Observed)"),
                new PublicHoliday(LocalDate.of(2026, 11, 8), "Deepavali"),
                new PublicHoliday(LocalDate.of(2026, 11, 9), "Deepavali (Observed)"),
                new PublicHoliday(LocalDate.of(2026, 12, 25), "Christmas Day")
        );

        holidays.stream()
                .filter(holiday -> !repository.existsByDate(holiday.getDate()))
                .forEach(repository::save);
    }
}