package app.service;

import app.holiday.PublicHoliday;
import app.holiday.PublicHolidayRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class PublicHolidayService {

    private final PublicHolidayRepository publicHolidayRepository;

    public PublicHolidayService(PublicHolidayRepository publicHolidayRepository) {
        this.publicHolidayRepository = publicHolidayRepository;
    }

    // The public holidays from one date to another, both included, oldest first.
    public List<LocalDate> holidaysBetween(LocalDate from, LocalDate to) {
        return publicHolidayRepository.findByDateBetweenOrderByDateAsc(from, to).stream()
                .map(PublicHoliday::getDate)
                .distinct()
                .toList();
    }
}