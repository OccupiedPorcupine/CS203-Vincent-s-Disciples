package app.service;

import app.closure.ClosedDate;
import app.closure.ClosedDateRepository;
import app.closure.HawkerCentre;
import app.closure.HawkerCentreClosure;
import app.closure.HawkerCentreClosureRepository;
import app.closure.HawkerCentreRepository;
import app.closure.Hours;
import app.closure.NeaOpenDay;
import app.closure.NeaOpenDayRepository;
import app.closure.RestDay;
import app.closure.RestDayRepository;
import app.closure.StallHawkerCentre;
import app.closure.StallHawkerCentreRepository;
import app.closure.TradingHours;
import app.closure.TradingHoursRepository;
import app.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

// The days a user's stall doesn't trade: weekly rest days, one-off closed dates the owner set, and
// the days NEA closes the stall's hawker centre (HawkerCentreSyncService), except days the owner
// says the stall opens anyway. Closed days aren't forecast, and the dashboard doesn't show them as
// days with no sales. Also the stall's trading hours, which tell how early in the day a dish sold out.
//
// NEA's dates aren't copied into closed_dates: they're read from the centre's closures, so they
// follow NEA's updates, an owner's own closed date always wins, and unlinking the centre drops them.
@Service
public class StallClosureService {

    static final int MAX_REASON_LENGTH = 100;
    // How far back and ahead NEA closures count as closed days. Long works (one centre is closed
    // until 2029) would otherwise mean years of days.
    static final int NEA_DAYS_BACK = 400;
    static final int NEA_DAYS_AHEAD = 400;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH);

    private final RestDayRepository restDayRepository;
    private final ClosedDateRepository closedDateRepository;
    private final TradingHoursRepository tradingHoursRepository;
    private final AppUserRepository appUserRepository;
    private final HawkerCentreRepository hawkerCentreRepository;
    private final HawkerCentreClosureRepository hawkerCentreClosureRepository;
    private final StallHawkerCentreRepository stallHawkerCentreRepository;
    private final NeaOpenDayRepository neaOpenDayRepository;
    private final Clock clock;

    public StallClosureService(RestDayRepository restDayRepository, ClosedDateRepository closedDateRepository,
                               TradingHoursRepository tradingHoursRepository, AppUserRepository appUserRepository,
                               HawkerCentreRepository hawkerCentreRepository,
                               HawkerCentreClosureRepository hawkerCentreClosureRepository,
                               StallHawkerCentreRepository stallHawkerCentreRepository,
                               NeaOpenDayRepository neaOpenDayRepository, Clock clock) {
        this.restDayRepository = restDayRepository;
        this.closedDateRepository = closedDateRepository;
        this.tradingHoursRepository = tradingHoursRepository;
        this.appUserRepository = appUserRepository;
        this.hawkerCentreRepository = hawkerCentreRepository;
        this.hawkerCentreClosureRepository = hawkerCentreClosureRepository;
        this.stallHawkerCentreRepository = stallHawkerCentreRepository;
        this.neaOpenDayRepository = neaOpenDayRepository;
        this.clock = clock;
    }

    // One closed day. fromNea is true for a day NEA closes the stall's centre.
    public record Closure(LocalDate date, String reason, boolean fromNea) {}

    public record LinkedCentre(Long id, String name) {}

    // An NEA closure of the stall's centre that hasn't ended, with the days in it the owner opens anyway.
    public record NeaClosure(LocalDate startDate, LocalDate endDate, String reason, String remarks, List<LocalDate> openDays) {}

    // hours is null when the stall hasn't set them; hawkerCentre is null when no centre is picked.
    public record StallClosures(Set<DayOfWeek> restDays, List<Closure> closedDates, Hours hours,
                                LinkedCentre hawkerCentre, List<NeaClosure> neaClosures) {}

    public StallClosures getClosures(Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        List<Closure> closed = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        closedDateRepository.findByOwnerIdOrderByClosedDateAsc(ownerId).forEach(closedDate -> {
            closed.add(new Closure(closedDate.getClosedDate(), closedDate.getReason(), false));
            seen.add(closedDate.getClosedDate());
        });

        LinkedCentre linked = null;
        List<NeaClosure> upcoming = List.of();
        Optional<StallHawkerCentre> link = stallHawkerCentreRepository.findByOwnerId(ownerId);
        if (link.isPresent()) {
            HawkerCentre centre = link.get().getCentre();
            linked = new LinkedCentre(centre.getId(), centre.getName());
            Set<LocalDate> openDays = openDays(ownerId);
            LocalDate from = today.minusDays(NEA_DAYS_BACK);
            LocalDate to = today.plusDays(NEA_DAYS_AHEAD);
            List<HawkerCentreClosure> ranges = neaClosures(centre.getId(), from, to);

            for (HawkerCentreClosure range : ranges) {
                for (LocalDate day : days(range, from, to)) {
                    if (!openDays.contains(day) && seen.add(day)) closed.add(new Closure(day, neaReason(range), true));
                }
            }
            upcoming = ranges.stream()
                    .filter(range -> !range.getEndDate().isBefore(today))
                    .map(range -> new NeaClosure(range.getStartDate(), range.getEndDate(), neaReason(range), range.getRemarks(),
                            openDays.stream()
                                    .filter(day -> !day.isBefore(range.getStartDate()) && !day.isAfter(range.getEndDate()))
                                    .sorted().toList()))
                    .toList();
        }
        closed.sort(Comparator.comparing(Closure::date));
        return new StallClosures(restDays(ownerId), closed, hours(ownerId).orElse(null), linked, upcoming);
    }

    public record HawkerCentreChoice(Long id, String name, String address) {}

    // NEA's hawker centres, for the owner to pick theirs.
    public List<HawkerCentreChoice> hawkerCentres() {
        return hawkerCentreRepository.findAllByOrderByNameAsc().stream()
                .map(centre -> new HawkerCentreChoice(centre.getId(), centre.getName(), centre.getAddress()))
                .toList();
    }

    // Links the stall to a hawker centre, or unlinks it when centreId is null. Days opened anyway
    // belonged to the old centre's closures, so they go too.
    @Transactional
    public StallClosures setHawkerCentre(Long ownerId, Long centreId) {
        Optional<StallHawkerCentre> link = stallHawkerCentreRepository.findByOwnerId(ownerId);
        if (centreId == null) {
            link.ifPresent(stallHawkerCentreRepository::delete);
            neaOpenDayRepository.deleteAllForOwner(ownerId);
            return getClosures(ownerId);
        }

        HawkerCentre centre = hawkerCentreRepository.findById(centreId)
                .orElseThrow(() -> new IllegalArgumentException("Hawker centre " + centreId + " isn't on NEA's list."));
        if (link.isPresent() && link.get().getCentre().getId().equals(centreId)) return getClosures(ownerId);

        neaOpenDayRepository.deleteAllForOwner(ownerId);
        link.ifPresentOrElse(
                saved -> saved.setCentre(centre),
                () -> stallHawkerCentreRepository.save(new StallHawkerCentre(appUserRepository.getReferenceById(ownerId), centre)));
        return getClosures(ownerId);
    }

    // Marks NEA's closed days from..to as open anyway (the stall trades regardless) or closed again.
    @Transactional
    public StallClosures setNeaDaysOpen(Long ownerId, LocalDate from, LocalDate to, boolean open) {
        if (to.isBefore(from)) throw new IllegalArgumentException("The last day must not be before the first.");
        if (from.plusDays(NEA_DAYS_BACK + NEA_DAYS_AHEAD).isBefore(to)) throw new IllegalArgumentException("That's too many days at once.");

        if (!open) {
            neaOpenDayRepository.deleteBetween(ownerId, from, to);
            return getClosures(ownerId);
        }
        Set<LocalDate> alreadyOpen = openDays(ownerId);
        Set<LocalDate> neaDays = neaDays(ownerId, from, to);
        neaOpenDayRepository.saveAll(neaDays.stream()
                .filter(day -> !alreadyOpen.contains(day))
                .sorted()
                .map(day -> new NeaOpenDay(appUserRepository.getReferenceById(ownerId), day))
                .toList());
        return getClosures(ownerId);
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

    // Marks a day as closed, or changes the reason if it already is. On a day NEA closes the centre
    // but the owner had opened anyway, closing it without a reason just brings back NEA's closure.
    @Transactional
    public StallClosures closeDate(Long ownerId, LocalDate date, String reason) {
        String cleaned = reason == null || reason.isBlank() ? null : reason.strip();
        if (cleaned != null && cleaned.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Reason must be at most " + MAX_REASON_LENGTH + " characters");
        }

        neaOpenDayRepository.deleteBetween(ownerId, date, date);
        Optional<ClosedDate> existing = closedDateRepository.findByOwnerIdAndClosedDate(ownerId, date);
        if (existing.isPresent()) {
            existing.get().setReason(cleaned);
        } else if (cleaned != null || neaDays(ownerId, date, date).isEmpty()) {
            closedDateRepository.save(new ClosedDate(appUserRepository.getReferenceById(ownerId), date, cleaned));
        }
        return getClosures(ownerId);
    }

    // Opens a day: removes the owner's own closure and, if NEA closes the centre that day, marks
    // it open anyway.
    @Transactional
    public StallClosures reopenDate(Long ownerId, LocalDate date) {
        closedDateRepository.findByOwnerIdAndClosedDate(ownerId, date).ifPresent(closedDateRepository::delete);
        if (!neaDays(ownerId, date, date).isEmpty() && !neaOpenDayRepository.existsByOwnerIdAndOpenDate(ownerId, date)) {
            neaOpenDayRepository.save(new NeaOpenDay(appUserRepository.getReferenceById(ownerId), date));
        }
        return getClosures(ownerId);
    }

    public boolean isRestDay(Long ownerId, LocalDate date) {
        return restDays(ownerId).contains(date.getDayOfWeek());
    }

    // Why the stall is closed on this date, in words for the user; empty when it is open.
    public Optional<String> closureMessage(Long ownerId, LocalDate date) {
        Optional<ClosedDate> closedDate = closedDateRepository.findByOwnerIdAndClosedDate(ownerId, date);
        if (closedDate.isPresent()) {
            String reason = closedDate.get().getReason();
            return Optional.of("You marked " + DAY.format(date) + " as closed" + (reason == null ? "" : " (" + reason + ")")
                    + ". Remove it on the Data page if you're opening after all.");
        }
        Optional<StallHawkerCentre> link = stallHawkerCentreRepository.findByOwnerId(ownerId);
        if (link.isPresent() && !neaOpenDayRepository.existsByOwnerIdAndOpenDate(ownerId, date)) {
            HawkerCentre centre = link.get().getCentre();
            Optional<HawkerCentreClosure> nea = neaClosures(centre.getId(), date, date).stream().findFirst();
            if (nea.isPresent()) {
                String why = nea.get().getKind() == HawkerCentreClosure.Kind.CLEANING ? "for cleaning" : "for works";
                return Optional.of(centre.getName() + " is closed " + why + " on " + DAY.format(date)
                        + ", by NEA's schedule. If your stall opens anyway, mark the day open on the Data page.");
            }
        }
        if (restDays(ownerId).contains(date.getDayOfWeek())) {
            return Optional.of("Your stall is closed on " + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    + "s. Change your rest days on the Data page if that's wrong.");
        }
        return Optional.empty();
    }

    // The days from..to that NEA closes the stall's centre, before any the owner opens anyway.
    private Set<LocalDate> neaDays(Long ownerId, LocalDate from, LocalDate to) {
        Set<LocalDate> days = new HashSet<>();
        stallHawkerCentreRepository.findByOwnerId(ownerId).ifPresent(link ->
                neaClosures(link.getCentre().getId(), from, to).forEach(range -> days.addAll(days(range, from, to))));
        return days;
    }

    // The days of a closure that fall within from..to.
    private static List<LocalDate> days(HawkerCentreClosure range, LocalDate from, LocalDate to) {
        LocalDate first = range.getStartDate().isBefore(from) ? from : range.getStartDate();
        LocalDate last = range.getEndDate().isAfter(to) ? to : range.getEndDate();
        return first.datesUntil(last.plusDays(1)).toList();
    }

    private List<HawkerCentreClosure> neaClosures(Long centreId, LocalDate from, LocalDate to) {
        return hawkerCentreClosureRepository
                .findByCentreIdAndEndDateGreaterThanEqualAndStartDateLessThanEqualOrderByStartDateAsc(centreId, from, to);
    }

    private Set<LocalDate> openDays(Long ownerId) {
        Set<LocalDate> days = new HashSet<>();
        neaOpenDayRepository.findByOwnerId(ownerId).forEach(day -> days.add(day.getOpenDate()));
        return days;
    }

    private static String neaReason(HawkerCentreClosure closure) {
        return closure.getKind() == HawkerCentreClosure.Kind.CLEANING ? "Centre cleaning (NEA)" : "Centre works (NEA)";
    }

    private Set<DayOfWeek> restDays(Long ownerId) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        restDayRepository.findByOwnerId(ownerId).forEach(restDay -> days.add(restDay.getDay()));
        return days;
    }
}
