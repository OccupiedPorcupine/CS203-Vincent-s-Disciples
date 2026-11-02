package app.service;

import app.closure.ClosedDate;
import app.closure.ClosedDateRepository;
import app.closure.Hours;
import app.closure.RestDay;
import app.closure.RestDayRepository;
import app.closure.TradingHours;
import app.closure.TradingHoursRepository;
import app.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// The days a user's stall doesn't trade: weekly rest days and one-off closed dates. Closed days
// aren't forecast, and the dashboard doesn't show them as days with no sales. Also the stall's
// trading hours, which tell how early in the day a dish sold out.
@Service
public class StallClosureService {

    static final int MAX_REASON_LENGTH = 100;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH);

    private final RestDayRepository restDayRepository;
    private final ClosedDateRepository closedDateRepository;
    private final TradingHoursRepository tradingHoursRepository;
    private final AppUserRepository appUserRepository;

    public StallClosureService(RestDayRepository restDayRepository, ClosedDateRepository closedDateRepository,
                               TradingHoursRepository tradingHoursRepository, AppUserRepository appUserRepository) {
        this.restDayRepository = restDayRepository;
        this.closedDateRepository = closedDateRepository;
        this.tradingHoursRepository = tradingHoursRepository;
        this.appUserRepository = appUserRepository;
    }

    // hours is null when the stall hasn't set them.
    public record StallClosures(Set<DayOfWeek> restDays, List<ClosedDate> closedDates, Hours hours) {}

    public StallClosures getClosures(Long ownerId) {
        return new StallClosures(restDays(ownerId), closedDateRepository.findByOwnerIdOrderByClosedDateAsc(ownerId),
                hours(ownerId).orElse(null));
    }

    public Optional<Hours> hours(Long ownerId) {
        return tradingHoursRepository.findByOwnerId(ownerId).map(TradingHours::getHours);
    }

    @Transactional
    public StallClosures setHours(Long ownerId, Hours hours) {
        tradingHoursRepository.findByOwnerId(ownerId).ifPresentOrElse(
                saved -> saved.set(hours),
                () -> tradingHoursRepository.save(new TradingHours(appUserRepository.getReferenceById(ownerId), hours)));
        return getClosures(ownerId);
    }

    @Transactional
    public StallClosures clearHours(Long ownerId) {
        tradingHoursRepository.findByOwnerId(ownerId).ifPresent(tradingHoursRepository::delete);
        return getClosures(ownerId);
    }

    // Replaces the weekly rest days. Rows are added and removed individually rather than all
    // deleted first, because Hibernate inserts before it deletes and the unique key would clash.
    @Transactional
    public StallClosures setRestDays(Long ownerId, Collection<DayOfWeek> days) {
        Set<DayOfWeek> wanted = days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
        if (wanted.size() == DayOfWeek.values().length) {
            throw new IllegalArgumentException("At least one day of the week must be open.");
        }

        List<RestDay> existing = restDayRepository.findByOwnerId(ownerId);
        restDayRepository.deleteAll(existing.stream().filter(restDay -> !wanted.contains(restDay.getDay())).toList());
        Set<DayOfWeek> kept = existing.stream().map(RestDay::getDay).collect(Collectors.toSet());
        restDayRepository.saveAll(wanted.stream()
                .filter(day -> !kept.contains(day))
                .map(day -> new RestDay(appUserRepository.getReferenceById(ownerId), day))
                .toList());
        return getClosures(ownerId);
    }

    // Marks a day as closed, or changes the reason if it already is.
    @Transactional
    public StallClosures closeDate(Long ownerId, LocalDate date, String reason) {
        String cleaned = reason == null || reason.isBlank() ? null : reason.strip();
        if (cleaned != null && cleaned.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason must be at most " + MAX_REASON_LENGTH + " characters");
        }

        closedDateRepository.findByOwnerIdAndClosedDate(ownerId, date).ifPresentOrElse(
                closedDate -> closedDate.setReason(cleaned),
                () -> closedDateRepository.save(new ClosedDate(appUserRepository.getReferenceById(ownerId), date, cleaned)));
        return getClosures(ownerId);
    }

    @Transactional
    public StallClosures reopenDate(Long ownerId, LocalDate date) {
        closedDateRepository.findByOwnerIdAndClosedDate(ownerId, date).ifPresent(closedDateRepository::delete);
        return getClosures(ownerId);
    }

    // Why the stall is closed on this date, in words for the user; empty when it is open.
    public Optional<String> closureMessage(Long ownerId, LocalDate date) {
        Optional<ClosedDate> closedDate = closedDateRepository.findByOwnerIdAndClosedDate(ownerId, date);
        if (closedDate.isPresent()) {
            String reason = closedDate.get().getReason();
            return Optional.of("You marked " + DAY.format(date) + " as closed" + (reason == null ? "" : " (" + reason + ")")
                    + ". Remove it on the Data page if you're opening after all.");
        }
        if (restDays(ownerId).contains(date.getDayOfWeek())) {
            return Optional.of("Your stall is closed on " + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + "s. Change your rest days on the Data page if that's wrong.");
        }
        return Optional.empty();
    }

    private Set<DayOfWeek> restDays(Long ownerId) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        restDayRepository.findByOwnerId(ownerId).forEach(restDay -> days.add(restDay.getDay()));
        return days;
    }
}
