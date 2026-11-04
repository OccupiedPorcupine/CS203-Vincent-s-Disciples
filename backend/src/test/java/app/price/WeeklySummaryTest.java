package app.price;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.SetDishPriceRequest;
import app.dto.WeeklySummaryResponse;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// The weekly summary (issue #56) says the dashboard's own numbers, in English and Chinese.
@SpringBootTest
class WeeklySummaryTest {

    // Stubbed so the test does not call data.gov.sg on startup.
    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @Autowired WeeklySummaryService summaryService;
    @Autowired RevenueService revenueService;
    @Autowired DishPriceService priceService;
    @Autowired DishPriceRepository priceRepository;
    @Autowired DishService dishService;
    @Autowired DailySaleService saleService;
    @Autowired DishRepository dishRepository;
    @Autowired DailySaleRepository saleRepository;
    @Autowired AppUserRepository users;

    // The week before is Monday 19 to Sunday 25 October; the week is Monday 26 October to Sunday 1 November.
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
    static final LocalDate SUNDAY = MONDAY.plusDays(6);
    Long owner;
    Dish chickenRice;
    Dish charSiew;

    @BeforeEach
    void setUp() {
        owner = users.save(AppUser.localUser(UUID.randomUUID() + "@example.com", "{pbkdf2}hash", "Hawker")).getId();
        chickenRice = dishService.createDish("Chicken rice", owner);
        charSiew = dishService.createDish("Char siew rice", owner);
    }

    @AfterEach
    void cleanUp() {
        priceRepository.deleteAll();
        saleRepository.deleteAll();
        dishRepository.deleteAll();
    }

    private void price(Dish dish, String price, String unitCost) {
        priceService.setPrice(owner, dish.getId(), new SetDishPriceRequest(new BigDecimal(price), new BigDecimal(unitCost), null));
    }

    @Test
    void theSummaryUsesTheDashboardsFiguresInBothLanguages() {
        for (int day = 0; day < 7; day++) {
            saleService.setSale(chickenRice.getId(), owner, MONDAY.minusDays(7).plusDays(day), 50);
            saleService.setSale(charSiew.getId(), owner, MONDAY.minusDays(7).plusDays(day), 50);
            saleService.setSale(chickenRice.getId(), owner, MONDAY.plusDays(day), 60);
            saleService.setSale(charSiew.getId(), owner, MONDAY.plusDays(day), 48);
        }
        saleService.setSale(chickenRice.getId(), owner, MONDAY, 60, null, 3);
        saleService.setSale(charSiew.getId(), owner, MONDAY.plusDays(1), 48, LocalTime.of(12, 40), null);
        saleService.setSale(charSiew.getId(), owner, MONDAY.plusDays(2), 48, LocalTime.of(13, 0), null);
        price(chickenRice, "4.50", "1.70");
        price(charSiew, "5.00", "2.05");

        WeeklySummaryResponse summary = summaryService.weekly(owner);

        assertEquals(MONDAY, summary.from());
        assertEquals(SUNDAY, summary.to());
        assertEquals(revenueService.summary(owner, MONDAY, SUNDAY).portions(), summary.portions());
        assertEquals(756, summary.portions()); // 7 × (60 + 48)
        assertEquals(700, summary.previousPortions());
        assertEquals(8, summary.changePercent()); // 56 more than 700
        assertEquals(revenueService.summary(owner, MONDAY, SUNDAY).revenue(), summary.revenue());
        assertEquals(new BigDecimal("3570.00"), summary.revenue()); // 420 × 4.50 + 336 × 5.00
        assertEquals(new WeeklySummaryResponse.DishCount("Chicken rice", 420), summary.topDish());
        assertEquals(revenueService.waste(owner, MONDAY, SUNDAY).leftoverCost(), summary.leftoverCost());
        assertEquals(new BigDecimal("5.10"), summary.leftoverCost()); // 3 × 1.70
        assertEquals(new WeeklySummaryResponse.DishCount("Char siew rice", 2), summary.mostSoldOut());

        assertEquals("In the 7 days to Sunday 1 November, you sold 756 portions, up 8% on the week before. "
                + "Revenue was $3,570. Chicken rice sold the most, 420 portions. "
                + "You had 3 portions left over, about $5.10 of food. Char siew rice sold out on 2 days.", summary.text().en());
        assertEquals("截至11月1日星期日的7天，你卖了756份，比前一周多8%。营业额是3,570元。卖得最多的是Chicken rice，420份。"
                + "剩下3份，大约5.10元的食物。Char siew rice有2天卖完。", summary.text().zh());
    }

    @Test
    void missingPricesAndLeftoversAreLeftOutRatherThanSaidAsZero() {
        // Only Wednesday to Friday: the week still ends on the latest sale, and nothing came before it.
        for (int day = 2; day < 5; day++) saleService.setSale(chickenRice.getId(), owner, MONDAY.plusDays(day), 1);

        WeeklySummaryResponse summary = summaryService.weekly(owner);

        assertEquals(MONDAY.plusDays(4), summary.to());
        assertNull(summary.changePercent());
        assertNull(summary.revenue());
        assertFalse(summary.wasteRecorded());
        assertEquals("In the 7 days to Friday 30 October, you sold 3 portions. Chicken rice sold the most, 3 portions.",
                summary.text().en());
        assertEquals("截至10月30日星期五的7天，你卖了3份。卖得最多的是Chicken rice，3份。", summary.text().zh());
    }

    @Test
    void withNoSalesThereIsNoWeekToSumUp() {
        WeeklySummaryResponse summary = summaryService.weekly(owner);

        assertNull(summary.from());
        assertEquals("No sales are recorded yet, so there is no week to sum up.", summary.text().en());
    }
}
