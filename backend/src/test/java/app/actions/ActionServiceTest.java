package app.actions;

import app.closure.ClosedDateRepository;
import app.closure.RestDayRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// The Actions needed rules on H2, on Wednesday 4 November 2026 in Singapore.
@SpringBootTest
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
}
