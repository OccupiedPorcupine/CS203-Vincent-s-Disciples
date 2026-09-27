package app.service;

import app.holiday.PublicHolidayRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class PublicHolidayService {

    private final PublicHolidayRepository publicHolidayRepository;

    public PublicHolidayService(PublicHolidayRepository publicHolidayRepository) {
        this.publicHolidayRepository = publicHolidayRepository;
    }

    public boolean isPublicHoliday(LocalDate date) {
        return publicHolidayRepository.existsByDate(date);
    }
}