package app.actions;

import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.ActionCard;
import app.holiday.PublicHoliday;
import app.holiday.PublicHolidayRepository;
import app.price.DishPriceService;
import app.price.PriceBook;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import app.service.StallClosureService;
import app.user.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

// The dashboard's Actions needed cards (issue #53): simple rules over the stall's own data, no
// model. Each card has a key naming exactly what it is about (a day, a file, a holiday), so a
// dismissed card stays hidden while a new one of the same kind still shows.
@Service
public class ActionService {

    static final int MISSING_SALES_DAYS = 14;
    static final int MAX_MISSING_SALES_CARDS = 3;
    static final int HOLIDAY_DAYS_AHEAD = 14;
    static final int UNPRICED_LOOKBACK_DAYS = 30;
    static final int MAX_IMPORT_CARDS = 2;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final DishRepository dishRepository;
    private final DailySaleRepository saleRepository;
    private final StallClosureService closureService;
    private final PublicHolidayRepository holidayRepository;
    private final TrainingDataSourceRepository sourceRepository;
    private final DishPriceService priceService;
    private final DismissedActionRepository dismissedRepository;
    private final AppUserRepository users;
    private final Clock clock;

    public ActionService(DishRepository dishRepository, DailySaleRepository saleRepository,
                         StallClosureService closureService, PublicHolidayRepository holidayRepository,
                         TrainingDataSourceRepository sourceRepository, DishPriceService priceService,
                         DismissedActionRepository dismissedRepository, AppUserRepository users, Clock clock) {
        this.dishRepository = dishRepository;
        this.saleRepository = saleRepository;
        this.closureService = closureService;
        this.holidayRepository = holidayRepository;
        this.sourceRepository = sourceRepository;
        this.priceService = priceService;
        this.dismissedRepository = dismissedRepository;
        this.users = users;
        this.clock = clock;
    }

    // Most urgent first. Dismissed cards are left out.
    @Transactional(readOnly = true)
    public List<ActionCard> actions(Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        Optional<DailySale> firstSale = saleRepository.findFirstByDishOwnerIdOrderBySaleDateAsc(ownerId);
        StallClosureService.StallClosures closures = closureService.getClosures(ownerId);

        List<ActionCard> cards = new ArrayList<>();
        if (firstSale.isEmpty()) {
            cards.add(firstSales());
        } else {
            cards.addAll(missingSales(ownerId, firstSale.get().getSaleDate(), today, closures));
        }
        cards.addAll(unimportedFiles(ownerId));
        unpricedDishes(ownerId, today).ifPresent(cards::add);
        upcomingHoliday(ownerId, today, closures).ifPresent(cards::add);

        Set<String> dismissed = dismissedRepository.findByOwnerId(ownerId).stream()
                .map(DismissedAction::getActionKey)
                .collect(Collectors.toSet());
        return cards.stream().filter(card -> !dismissed.contains(card.key())).toList();
    }

    @Transactional
    public void dismiss(Long ownerId, String key) {
        if (!dismissedRepository.existsByOwnerIdAndActionKey(ownerId, key)) {
            dismissedRepository.save(new DismissedAction(users.getReferenceById(ownerId), key, clock.instant()));
        }
    }

    // ---- Rules ----

    private static Set<LocalDate> closedDates(StallClosureService.StallClosures closures) {
        return closures.closedDates().stream().map(closedDate -> closedDate.getClosedDate()).collect(Collectors.toSet());
    }

    private static ActionCard firstSales() {
        return new ActionCard("first-sales", "first-sales", "Record your first sales",
                "Tell the assistant what you sold today, or import a file of past sales.",
                "Import a file", "data.html", null);
    }

    // Past days in the last two weeks with no sales at all, newest first. Today is left out (the
    // day isn't over), and so are rest days, closed days and days before the stall's first sale.
    private List<ActionCard> missingSales(Long ownerId, LocalDate firstSale, LocalDate today,
                                          StallClosureService.StallClosures closures) {
        LocalDate from = firstSale.isAfter(today.minusDays(MISSING_SALES_DAYS)) ? firstSale : today.minusDays(MISSING_SALES_DAYS);
        Set<LocalDate> withSales = saleRepository.findByDishOwnerIdAndSaleDateBetween(ownerId, from, today).stream()
                .map(DailySale::getSaleDate)
                .collect(Collectors.toSet());
        Set<LocalDate> closed = closedDates(closures);

        List<ActionCard> cards = new ArrayList<>();
        for (LocalDate day = today.minusDays(1); !day.isBefore(from) && cards.size() < MAX_MISSING_SALES_CARDS; day = day.minusDays(1)) {
            if (withSales.contains(day) || closed.contains(day) || closures.restDays().contains(day.getDayOfWeek())) {
                continue;
            }
            cards.add(new ActionCard("missing-sales:" + day, "missing-sales",
                    "You haven't logged sales for " + day.format(DAY),
                    "Add them so your charts and forecasts stay accurate. If you were closed, mark the day closed on the Data page.",
                    "Add sales", null, day));
        }
        return cards;
    }

    private List<ActionCard> unimportedFiles(Long ownerId) {
        return sourceRepository.findAllByOwnerIdOrderByUploadedAtDesc(ownerId).stream()
                .filter(source -> source.getImportedAt() == null)
                .limit(MAX_IMPORT_CARDS)
                .map(ActionService::importCard)
                .toList();
    }

    private static ActionCard importCard(TrainingDataSource source) {
        return new ActionCard("import:" + source.getId(), "import",
                "Import the sales in " + source.getOriginalFilename(),
                "The file is uploaded but its sales aren't in your records yet.",
                "Import", "data.html", null);
    }

    // Dishes that sold in the last 30 days but have no price, so revenue leaves them out.
    private Optional<ActionCard> unpricedDishes(Long ownerId, LocalDate today) {
        PriceBook prices = priceService.priceBook(ownerId);
        Set<Long> sold = saleRepository.findByDishOwnerIdAndSaleDateBetween(ownerId, today.minusDays(UNPRICED_LOOKBACK_DAYS), today)
                .stream()
                .filter(sale -> sale.getQuantitySold() > 0)
                .map(sale -> sale.getDish().getId())
                .collect(Collectors.toSet());
        List<Dish> unpriced = dishRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .filter(dish -> sold.contains(dish.getId()) && !prices.hasPrice(dish.getId()))
                .toList();
        if (unpriced.isEmpty()) {
            return Optional.empty();
        }

        String names = unpriced.size() == 1 ? unpriced.getFirst().getName()
                : unpriced.size() == 2 ? unpriced.get(0).getName() + " and " + unpriced.get(1).getName()
                : unpriced.getFirst().getName() + " and " + (unpriced.size() - 1) + " other dishes";
        // A new unpriced dish changes the key, so the card comes back after being dismissed.
        String ids = unpriced.stream().map(dish -> String.valueOf(dish.getId())).collect(Collectors.joining(","));
        return Optional.of(new ActionCard("prices:" + Integer.toHexString(ids.hashCode()), "prices",
                "Set prices for " + names,
                "Then the dashboard can show your revenue and profit.",
                "Set prices", "dishes.html", null));
    }

    // The next public holiday in the coming two weeks, unless the stall is closed that day. When
    // there is data for the same holiday last year, says how it compared with a usual day.
    private Optional<ActionCard> upcomingHoliday(Long ownerId, LocalDate today, StallClosureService.StallClosures closures) {
        Set<LocalDate> closed = closedDates(closures);
        Optional<PublicHoliday> next = holidayRepository.findByDateBetweenOrderByDateAsc(today, today.plusDays(HOLIDAY_DAYS_AHEAD)).stream()
                .filter(holiday -> !closed.contains(holiday.getDate()) && !closures.restDays().contains(holiday.getDate().getDayOfWeek()))
                .findFirst();
        if (next.isEmpty()) {
            return Optional.empty();
        }
        PublicHoliday holiday = next.get();
        long days = ChronoUnit.DAYS.between(today, holiday.getDate());
        String when = days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";

        return Optional.of(new ActionCard("holiday:" + holiday.getDate(), "holiday",
                holiday.getName() + " is " + when + " (" + holiday.getDate().format(DAY) + ")",
                lastYear(ownerId, holiday).orElse("Check the forecast for that day and plan your prep."),
                "See the forecast", "forecast.html", null));
    }

    // "Last Deepavali you sold 30% more than a usual Sunday." Compares all dishes' sales on the
    // same holiday last year with the same weekday in the four weeks before it.
    private Optional<String> lastYear(Long ownerId, PublicHoliday holiday) {
        LocalDate date = holiday.getDate();
        Optional<PublicHoliday> previous = holidayRepository.findByNameAndDateBetween(holiday.getName(), date.minusDays(400), date.minusDays(300))
                .stream().findFirst();
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        LocalDate then = previous.get().getDate();
        Map<LocalDate, Integer> totals = new HashMap<>();
        saleRepository.findByDishOwnerIdAndSaleDateBetween(ownerId, then.minusWeeks(4), then)
                .forEach(sale -> totals.merge(sale.getSaleDate(), sale.getQuantitySold(), Integer::sum));

        Integer onHoliday = totals.get(then);
        List<Integer> usual = new ArrayList<>();
        for (int week = 1; week <= 4; week++) {
            Integer total = totals.get(then.minusWeeks(week));
            if (total != null) usual.add(total);
        }
        if (onHoliday == null || usual.isEmpty()) {
            return Optional.empty();
        }
        double baseline = usual.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (baseline <= 0) {
            return Optional.empty();
        }
        long change = Math.round((onHoliday - baseline) / baseline * 100);
        String weekday = then.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        if (Math.abs(change) < 5) {
            return Optional.of("Last " + holiday.getName() + " you sold about the same as a usual " + weekday + ".");
        }
        return Optional.of("Last " + holiday.getName() + " you sold " + Math.abs(change) + "% "
                + (change > 0 ? "more" : "less") + " than a usual " + weekday + ".");
    }
}
