package app.price;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.RevenueResponse;
import app.dto.SetDishPriceRequest;
import app.sale.DailySaleRepository;
import app.service.DailySaleService;
import app.service.DishService;
import app.service.PublicHolidaySyncService;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PriceAndRevenueTest {

    // Stubbed so the test does not call data.gov.sg on startup.
    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired DishPriceService priceService;
    @Autowired RevenueService revenueService;
    @Autowired DishPriceRepository priceRepository;
    @Autowired DishService dishService;
    @Autowired DailySaleService saleService;
    @Autowired DishRepository dishRepository;
    @Autowired DailySaleRepository saleRepository;
    @Autowired AppUserRepository users;
    @Autowired Clock clock;
    @Autowired app.service.StallClosureService closureService;
    @Autowired app.closure.RestDayRepository restDayRepository;

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    static final LocalDate SUNDAY = MONDAY.plusDays(6);
    Long owner;
    Dish chickenRice;
    Dish charSiew;
    Dish laksa;

    @BeforeEach
    void setUp() {
        owner = users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
        chickenRice = dishService.createDish("Chicken rice", owner);
        charSiew = dishService.createDish("Char siew rice", owner);
        laksa = dishService.createDish("Laksa", owner);
        for (int day = 0; day < 7; day++) {
            saleService.setSale(chickenRice.getId(), owner, MONDAY.plusDays(day), 40);
            saleService.setSale(charSiew.getId(), owner, MONDAY.plusDays(day), 20);
            saleService.setSale(laksa.getId(), owner, MONDAY.plusDays(day), 10);
        }
    }

    // Other tests share this database and delete users, so remove everything that points at them.
    @AfterEach
    void cleanUp() {
        restDayRepository.deleteAll();
        priceRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
    }

    private void price(Dish dish, String price, String unitCost, LocalDate from) {
        priceService.setPrice(owner, dish.getId(), new SetDishPriceRequest(
                new BigDecimal(price), unitCost == null ? null : new BigDecimal(unitCost), from));
    }

    private static RevenueResponse.DishRevenue dish(RevenueResponse revenue, String name) {
        return revenue.dishes().stream().filter(dish -> dish.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void weeklyRevenueIsQuantityTimesPriceAndUnpricedDishesAreNotCountedAsZero() {
        price(chickenRice, "4.50", "1.70", null);
        price(charSiew, "5.00", "2.00", null);

        RevenueResponse week = revenueService.summary(owner, MONDAY, SUNDAY);

        // The first price is set today but values the earlier week too.
        assertEquals(490, week.portions());
        assertEquals(new BigDecimal("1960.00"), week.revenue()); // 280 × 4.50 + 140 × 5.00
        assertEquals(new BigDecimal("1204.00"), week.grossProfit()); // 280 × 2.80 + 140 × 3.00
        assertEquals(70, week.unpricedPortions());
        assertNull(dish(week, "Laksa").revenue());
        assertNull(dish(week, "Laksa").price());
        assertEquals(List.of("Chicken rice", "Char siew rice", "Laksa"),
                week.dishes().stream().map(RevenueResponse.DishRevenue::name).toList());
        assertEquals(new BigDecimal("2.80"), dish(week, "Chicken rice").marginPerPortion());
    }

    @Test
    void dailyRevenueAddsUpToTheWeekAndMarksRestDays() {
        price(chickenRice, "4.50", null, null);
        price(charSiew, "5.00", null, null);
        closureService.setRestDays(owner, List.of(java.time.DayOfWeek.SUNDAY));

        var daily = revenueService.daily(owner, MONDAY, SUNDAY);

        assertTrue(daily.anyPrices());
        assertEquals(7, daily.days().size());
        assertEquals(new BigDecimal("280.00"), daily.days().getFirst().revenue()); // 40 × 4.50 + 20 × 5.00; laksa has no price
        assertEquals(70, daily.days().getFirst().portions());
        assertEquals(revenueService.summary(owner, MONDAY, SUNDAY).revenue(),
                daily.days().stream().map(day -> day.revenue()).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertFalse(daily.days().getFirst().closed());
        assertTrue(daily.days().getLast().closed());

        var before = revenueService.daily(owner, MONDAY.minusDays(3), MONDAY.minusDays(1));
        assertEquals(0, before.days().getFirst().portions());
        assertNull(before.days().getFirst().revenue());
        assertThrows(IllegalArgumentException.class, () -> revenueService.daily(owner, MONDAY.minusDays(400), SUNDAY));
    }

    @Test
    void aPriceChangeOnlyAffectsSalesFromItsDate() {
        price(chickenRice, "4.00", null, MONDAY.minusMonths(1));
        price(chickenRice, "5.00", null, MONDAY.plusDays(4)); // from Friday

        RevenueResponse week = revenueService.summary(owner, MONDAY, SUNDAY);

        assertEquals(new BigDecimal("1240.00"), dish(week, "Chicken rice").revenue()); // 4 × 40 × 4.00 + 3 × 40 × 5.00
        assertEquals(new BigDecimal("5.00"), dish(week, "Chicken rice").price());
        assertEquals(new BigDecimal("640.00"), revenueService.summary(owner, MONDAY, MONDAY.plusDays(3)).revenue());
    }

    @Test
    void grossProfitLeavesOutDishesWhoseCostIsNotKnown() {
        price(chickenRice, "4.50", "1.70", null);
        price(charSiew, "5.00", null, null);

        RevenueResponse week = revenueService.summary(owner, MONDAY, SUNDAY);

        assertEquals(new BigDecimal("1960.00"), week.revenue());
        assertEquals(new BigDecimal("784.00"), week.grossProfit()); // chicken rice only
        assertEquals(140, week.portionsWithoutCost());
        assertNull(dish(week, "Char siew rice").grossProfit());
        assertNull(dish(week, "Char siew rice").marginPerPortion());
    }

    @Test
    void settingAPriceForTheSameDayReplacesItAndTheListShowsTodaysPrice() {
        price(chickenRice, "4.50", null, null);
        price(chickenRice, "4.80", "1.80", null);
        price(chickenRice, "5.20", "1.80", LocalDate.now(clock).plusMonths(1));

        assertEquals(2, priceService.history(owner, chickenRice.getId()).size());
        PriceBook.Price today = priceService.priceBook(owner).on(chickenRice.getId(), LocalDate.now(clock)).orElseThrow();
        assertEquals(new BigDecimal("4.80"), today.price());
    }

    @Test
    void importsOnlySetAPriceWhenTheDishHasNone() {
        price(chickenRice, "4.50", null, null);

        assertFalse(priceService.setPriceIfMissing(chickenRice, MONDAY, new BigDecimal("9.99"), null));
        assertTrue(priceService.setPriceIfMissing(laksa, MONDAY, new BigDecimal("6.00"), new BigDecimal("2.50")));
        assertEquals(new BigDecimal("4.50"), priceService.priceBook(owner).on(chickenRice.getId(), MONDAY).orElseThrow().price());
    }

    @Test
    void anotherUsersDishAndFarFutureDatesAreRefused() {
        Long other = users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Other")).getId();
        Dish theirs = dishService.createDish("Chicken rice", other);

        assertThrows(IllegalArgumentException.class, () -> priceService.setPrice(owner, theirs.getId(),
                new SetDishPriceRequest(new BigDecimal("1.00"), null, null)));
        assertThrows(IllegalArgumentException.class, () -> price(chickenRice, "4.50", null, LocalDate.now(clock).plusYears(2)));
        assertThrows(IllegalArgumentException.class, () -> revenueService.summary(owner, SUNDAY, MONDAY));
    }
}
