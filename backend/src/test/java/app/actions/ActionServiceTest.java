package app.actions;

import app.closure.ClosedDateRepository;
import app.closure.Hours;
import app.closure.RestDayRepository;
import app.closure.TradingHoursRepository;
import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.ActionCard;
import app.dto.SetDishPriceRequest;
import app.holiday.PublicHoliday;
import app.holiday.PublicHolidayRepository;
import app.price.DishPriceRepository;
import app.price.DishPriceService;
import app.sale.DailySaleRepository;
import app.service.DailySaleService;
import app.service.DishService;
import app.service.PublicHolidaySyncService;
import app.service.StallClosureService;
import app.user.AppUser;
import app.user.AppUserRepository;
import app.weather.WeatherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// The Actions needed rules on H2, on Wednesday 4 November 2026 in Singapore, with sold-out days
// counted in forecasts (so the trading-hours card can show).
@SpringBootTest(properties = "app.forecast.count-sold-out-demand=true")
class ActionServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-04T04:00:00Z"), ZoneId.of("Asia/Singapore"));
        }
    }

    // Stubbed so the test does not call data.gov.sg on startup.
    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    // No outlook and no rainfall unless a test says so, so no rain card.
    @MockitoBean
    WeatherService weatherService;

    @Autowired ActionService actionService;
    @Autowired DismissedActionRepository dismissedRepository;
    @Autowired DishService dishService;
    @Autowired DailySaleService saleService;
    @Autowired DishRepository dishRepository;
    @Autowired DailySaleRepository saleRepository;
    @Autowired DishPriceService priceService;
    @Autowired DishPriceRepository priceRepository;
    @Autowired StallClosureService closureService;
    @Autowired ClosedDateRepository closedDateRepository;
    @Autowired RestDayRepository restDayRepository;
    @Autowired TradingHoursRepository tradingHoursRepository;
    @Autowired PublicHolidayRepository holidayRepository;
    @Autowired TrainingDataSourceRepository sourceRepository;
    @Autowired AppUserRepository users;

    Long owner;
    Dish chickenRice;
    final List<Long> holidays = new ArrayList<>();

    @BeforeEach
    void setUp() {
        owner = users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
        chickenRice = dishService.createDish("Chicken rice", owner);
        priceService.setPrice(owner, chickenRice.getId(), new SetDishPriceRequest(new BigDecimal("4.50"), null, LocalDate.of(2025, 1, 1)));
    }

    // Other tests share this database and delete users, so remove everything that points at them.
    @AfterEach
    void cleanUp() {
        dismissedRepository.deleteAll();
        sourceRepository.deleteAll();
        closedDateRepository.deleteAll();
        restDayRepository.deleteAll();
        tradingHoursRepository.deleteAll();
        priceRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
        holidayRepository.deleteAllById(holidays);
    }

    private void sell(Dish dish, LocalDate from, LocalDate to, int quantity, LocalDate... except) {
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (!List.of(except).contains(day)) saleService.setSale(dish.getId(), owner, day, quantity);
        }
    }

    private void holiday(LocalDate date, String name) {
        holidays.add(holidayRepository.save(new PublicHoliday(date, name)).getId());
    }

    private List<String> keys() {
        return actionService.actions(owner).stream().map(ActionCard::key).toList();
    }

    @Test
    void aStallWithNoSalesIsAskedToRecordItsFirst() {
        assertEquals(List.of("first-sales"), keys());
    }

    @Test
    void pastDaysWithoutSalesAreListedButNotRestDaysClosedDaysTodayOrBeforeTheFirstSale() {
        // Sales from Tue 20 Oct to yesterday, except Fri 30 Oct (missing), Sun 1 Nov (rest day)
        // and Mon 2 Nov (closed for cleaning). Nothing yet today.
        sell(chickenRice, LocalDate.of(2026, 10, 20), TODAY.minusDays(1), 40,
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2));
        closureService.setRestDays(owner, List.of(DayOfWeek.SUNDAY));
        closureService.closeDate(owner, LocalDate.of(2026, 11, 2), "Centre cleaning");

        List<ActionCard> cards = actionService.actions(owner);

        assertEquals(List.of("missing-sales:2026-10-30"), cards.stream().map(ActionCard::key).toList());
        ActionCard card = cards.getFirst();
        assertEquals("You haven't logged sales for Fri 30 Oct", card.title());
        assertEquals(LocalDate.of(2026, 10, 30), card.recordDate());
        assertNull(card.href());
    }

    @Test
    void aDismissedDayStaysHiddenButAnotherMissingDayStillShows() {
        sell(chickenRice, LocalDate.of(2026, 10, 25), TODAY.minusDays(1), 40, LocalDate.of(2026, 10, 30));

        actionService.dismiss(owner, "missing-sales:2026-10-30");
        actionService.dismiss(owner, "missing-sales:2026-10-30"); // twice is harmless
        assertEquals(List.of(), keys());

        saleRepository.delete(saleRepository.findByDishIdAndSaleDate(chickenRice.getId(), TODAY.minusDays(1)).orElseThrow());
        assertEquals(List.of("missing-sales:2026-11-03"), keys());
    }

    @Test
    void anUnpricedDishAndAnUploadThatWasNeverImportedGetCards() {
        sell(chickenRice, TODAY.minusDays(7), TODAY.minusDays(1), 40);
        Dish laksa = dishService.createDish("Laksa", owner);
        saleService.setSale(laksa.getId(), owner, TODAY.minusDays(1), 12);
        AppUser user = users.findById(owner).orElseThrow();
        TrainingDataSource source = sourceRepository.save(new TrainingDataSource(user, "pos_export.csv", "text/csv", 10, "a".repeat(64), "/tmp/x"));

        List<ActionCard> cards = actionService.actions(owner);

        assertEquals(List.of("import", "prices"), cards.stream().map(ActionCard::kind).toList());
        assertEquals("Import the sales in pos_export.csv", cards.get(0).title());
        assertEquals("import:" + source.getId(), cards.get(0).key());
        assertEquals("Set prices for Laksa", cards.get(1).title());
        assertEquals("dishes.html", cards.get(1).href());

        priceService.setPrice(owner, laksa.getId(), new SetDishPriceRequest(new BigDecimal("6.00"), null, null));
        source.recordImport(Instant.now(), 1, 0, 0);
        sourceRepository.save(source);
        assertEquals(List.of(), keys());
    }

    @Test
    void anUpcomingHolidaySaysHowTheSameHolidayWentLastYear() {
        sell(chickenRice, TODAY.minusDays(7), TODAY.minusDays(1), 40);
        LocalDate lastYear = LocalDate.of(2025, 10, 20); // a Monday
        holiday(lastYear, "Deepavali Test");
        holiday(TODAY.plusDays(5), "Deepavali Test");
        for (int week = 1; week <= 4; week++) saleService.setSale(chickenRice.getId(), owner, lastYear.minusWeeks(week), 40);
        saleService.setSale(chickenRice.getId(), owner, lastYear, 60);

        ActionCard card = actionService.actions(owner).getLast();

        assertEquals("holiday:2026-11-09", card.key());
        assertEquals("Deepavali Test is in 5 days (Mon 9 Nov)", card.title());
        assertEquals("Last Deepavali Test you sold 50% more than a usual Monday.", card.detail());
        assertEquals("forecast.html", card.href());
    }

    @Test
    void noHolidayCardWhenTheStallIsClosedThatDay() {
        sell(chickenRice, TODAY.minusDays(7), TODAY.minusDays(1), 40);
        holiday(TODAY.plusDays(5), "Closed Holiday Test");
        closureService.closeDate(owner, TODAY.plusDays(5), "Holiday");

        assertTrue(keys().stream().noneMatch(key -> key.startsWith("holiday:")));
    }

    @Test
    void aStallWhoseDishesSellOutIsAskedForItsTradingHoursOnce() {
        sell(chickenRice, TODAY.minusDays(7), TODAY.minusDays(1), 40);
        assertTrue(keys().stream().noneMatch(key -> key.equals("trading-hours")));

        saleService.setSale(chickenRice.getId(), owner, TODAY.minusDays(1), 40, LocalTime.of(12, 40), null);
        ActionCard card = actionService.actions(owner).stream().filter(c -> c.key().equals("trading-hours")).findFirst().orElseThrow();
        assertEquals("data.html#trading-hours", card.href());

        closureService.setHours(owner, new Hours(LocalTime.of(10, 0), LocalTime.of(15, 0)));
        assertTrue(keys().stream().noneMatch(key -> key.equals("trading-hours")));
    }

    @Test
    void aDishThatSoldOutTwiceThisWeekGetsACardThatComesBackAfterTheNextSellOut() {
        sell(chickenRice, TODAY.minusDays(7), TODAY.minusDays(1), 40);
        Dish charSiew = dishService.createDish("Char siew rice", owner);
        priceService.setPrice(owner, charSiew.getId(), new SetDishPriceRequest(new BigDecimal("5.00"), null, LocalDate.of(2025, 1, 1)));
        closureService.setHours(owner, new Hours(LocalTime.of(10, 0), LocalTime.of(15, 0)));
        saleService.setSale(charSiew.getId(), owner, TODAY.minusDays(3), 30, LocalTime.of(14, 0), null); // 4 of 5 hours: 37.5
        saleService.setSale(charSiew.getId(), owner, TODAY.minusDays(1), 30, LocalTime.of(12, 30), null); // capped: 45
        saleService.setSale(chickenRice.getId(), owner, TODAY.minusDays(2), 40, LocalTime.of(14, 30), null); // only once
        saleService.setSale(charSiew.getId(), owner, TODAY.minusDays(9), 30, LocalTime.of(11, 0), null); // before this week

        ActionCard card = actionService.actions(owner).stream().filter(c -> c.kind().equals("sold-out")).findFirst().orElseThrow();
        assertEquals("sold-out:" + charSiew.getId() + ":" + TODAY.minusDays(1), card.key());
        assertEquals("Char siew rice sold out 2 times this week", card.title());
        assertEquals("Earliest at 12:30. You'd likely have sold about 11 more on those days, so consider preparing extra.", card.detail());
        assertEquals("prep.html", card.href());
        assertEquals(1, keys().stream().filter(key -> key.startsWith("sold-out:")).count());

        actionService.dismiss(owner, card.key());
        assertTrue(keys().stream().noneMatch(key -> key.startsWith("sold-out:")));
        saleService.setSale(charSiew.getId(), owner, TODAY, 30, LocalTime.of(13, 0), null);
        assertEquals("Char siew rice sold out 3 times this week",
                actionService.actions(owner).stream().filter(c -> c.kind().equals("sold-out")).findFirst().orElseThrow().title());
    }

    // Sells 100 a day for the 12 weeks before today, except where sales says otherwise, and reports
    // those days dry except where rain says otherwise.
    private void weeks(Map<LocalDate, Integer> sales, Map<LocalDate, Double> rain) {
        Map<LocalDate, Double> rainfall = new HashMap<>();
        for (LocalDate day = TODAY.minusWeeks(12); day.isBefore(TODAY); day = day.plusDays(1)) {
            saleService.setSale(chickenRice.getId(), owner, day, sales.getOrDefault(day, 100));
            rainfall.put(day, rain.getOrDefault(day, 0.0));
        }
        when(weatherService.rainfall(any(), any())).thenReturn(rainfall);
    }

    private ActionCard rainCard() {
        return actionService.actions(owner).stream().filter(card -> card.kind().equals("rain")).findFirst().orElse(null);
    }

    @Test
    void showersTomorrowSayHowTheLastRainyDaysOfThatWeekdayWent() {
        LocalDate thu = LocalDate.of(2026, 10, 29);
        // The last 4 rainy Thursdays sold 80; an older rainy one (50) and a drizzly one (3 mm) don't count.
        weeks(Map.of(thu, 80, thu.minusWeeks(1), 80, thu.minusWeeks(2), 80, thu.minusWeeks(3), 80, thu.minusWeeks(4), 50),
                Map.of(thu, 12.0, thu.minusWeeks(1), 30.0, thu.minusWeeks(2), 5.0, thu.minusWeeks(3), 8.0,
                        thu.minusWeeks(4), 20.0, thu.minusWeeks(5), 3.0));
        when(weatherService.outlook()).thenReturn(List.of(
                new WeatherService.Forecast(TODAY.plusDays(1), "Thundery Showers"),
                new WeatherService.Forecast(TODAY.plusDays(2), "Fair")));

        ActionCard card = rainCard();

        assertEquals("rain:2026-11-05", card.key());
        assertEquals("Thundery showers forecast tomorrow (Thu 5 Nov)", card.title());
        assertEquals("On your last 4 rainy Thursdays you sold 20% less than on dry ones, so consider preparing less.", card.detail());
        assertEquals("forecast.html", card.href());

        actionService.dismiss(owner, card.key());
        assertNull(rainCard());
    }

    @Test
    void rainyDaysThatMadeNoDifferenceAndClosedDaysAreSkipped() {
        LocalDate fri = LocalDate.of(2026, 10, 30);
        LocalDate sat = LocalDate.of(2026, 10, 31);
        // Rainy Fridays sold as usual; rainy Saturdays sold 30% more. Thursdays sold less, but the stall closes tomorrow.
        weeks(Map.of(sat, 130, sat.minusWeeks(1), 130, LocalDate.of(2026, 10, 29), 50, LocalDate.of(2026, 10, 22), 50),
                Map.of(fri, 15.0, fri.minusWeeks(1), 15.0, sat, 15.0, sat.minusWeeks(1), 15.0,
                        LocalDate.of(2026, 10, 29), 15.0, LocalDate.of(2026, 10, 22), 15.0));
        closureService.closeDate(owner, TODAY.plusDays(1), "Centre cleaning");
        when(weatherService.outlook()).thenReturn(List.of(
                new WeatherService.Forecast(TODAY.plusDays(1), "Thundery Showers"),
                new WeatherService.Forecast(TODAY.plusDays(2), "Showers"),
                new WeatherService.Forecast(TODAY.plusDays(3), "Showers")));

        ActionCard card = rainCard();

        assertEquals("rain:2026-11-07", card.key());
        assertEquals("Showers forecast for Sat 7 Nov", card.title());
        assertEquals("On your last 2 rainy Saturdays you sold 30% more than on dry ones, so consider preparing extra.", card.detail());
    }

    @Test
    void noRainCardWithoutEnoughRainyDaysOrWithoutRainfall() {
        LocalDate thu = LocalDate.of(2026, 10, 29);
        weeks(Map.of(thu, 50), Map.of(thu, 15.0)); // only one rainy Thursday
        when(weatherService.outlook()).thenReturn(List.of(new WeatherService.Forecast(TODAY.plusDays(1), "Thundery Showers")));
        assertNull(rainCard());

        when(weatherService.rainfall(any(), any())).thenReturn(Map.of()); // Open-Meteo unreachable
        assertNull(rainCard());
    }
}
