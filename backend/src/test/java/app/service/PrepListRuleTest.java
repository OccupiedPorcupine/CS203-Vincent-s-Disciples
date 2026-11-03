package app.service;

import app.dto.ForecastResponse;
import app.dto.PrepListResponse.Rule;
import app.price.PriceBook;
import app.service.BaselineForecaster.Observation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// How the prep list chooses each dish's portions (#86).
class PrepListRuleTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    // A forecast of 40 (likely 34-47) from 3 days of sales: too few errors to measure, so the
    // margin quantile follows the rough +/- 25% range.
    private static final ForecastService.WithHistory FORECAST = new ForecastService.WithHistory(
            new ForecastResponse("Chicken rice", DAY, 40.0, 34, 47, true, ForecastResponse.Method.BASELINE, null,
                    "Average of your last 3 days of sales", null),
            List.of(new Observation(DAY.minusDays(3), 38), new Observation(DAY.minusDays(2), 40), new Observation(DAY.minusDays(1), 42)));

    private static Optional<PriceBook.Price> price(String price, String cost) {
        return Optional.of(new PriceBook.Price(DAY.minusDays(30), new BigDecimal(price), cost == null ? null : new BigDecimal(cost)));
    }

    @Test
    void aDishWithAPriceAndCostIsPreparedToItsMargin() {
        PrepListService.Choice choice = PrepListService.choose(Rule.MARGIN, null, FORECAST, price("4.50", "1.60"));

        // (4.50 - 1.60) / 4.50 = 0.644; 40 x (1 + 0.25 x (0.644 - 0.5) / 0.4) = 43.6.
        assertEquals(44, choice.portions());
        assertEquals(Rule.MARGIN, choice.rule());
        assertEquals("Enough for about 64% of days: running out loses $2.90 profit a plate, a leftover wastes $1.60.", choice.reason());
    }

    @Test
    void aThinMarginPreparesLessThanAWideOne() {
        int thin = PrepListService.choose(Rule.MARGIN, null, FORECAST, price("4.00", "3.00")).portions();
        int wide = PrepListService.choose(Rule.MARGIN, null, FORECAST, price("4.00", "0.80")).portions();

        assertEquals(34, thin);   // 25% of days: 40 x (1 + 0.25 x (0.25 - 0.5) / 0.4) = 33.75
        assertEquals(48, wide);   // 80% of days: 40 x (1 + 0.25 x (0.80 - 0.5) / 0.4) = 47.5
    }

    @Test
    void withoutAPriceAndCostTheTopOfTheRangeIsUsedAndExplained() {
        for (Optional<PriceBook.Price> missing : List.of(Optional.<PriceBook.Price>empty(), price("4.50", null))) {
            PrepListService.Choice choice = PrepListService.choose(Rule.MARGIN, null, FORECAST, missing);
            assertEquals(47, choice.portions());
            assertEquals(Rule.RANGE, choice.rule());
            assertEquals("Top of the likely range. Add a price and cost on the Dishes page to prepare by profit instead.", choice.reason());
        }

        PrepListService.Choice loss = PrepListService.choose(Rule.MARGIN, null, FORECAST, price("3.00", "3.20"));
        assertEquals(Rule.RANGE, loss.rule());
        assertEquals("Top of the likely range: its cost isn't below its price. Check them on the Dishes page.", loss.reason());
    }

    @Test
    void theOtherRulesIgnoreThePrice() {
        PrepListService.Choice range = PrepListService.choose(Rule.RANGE, null, FORECAST, price("4.50", "1.60"));
        assertEquals(47, range.portions());
        assertNull(range.reason());

        PrepListService.Choice buffer = PrepListService.choose(Rule.BUFFER, 20, FORECAST, price("4.50", "1.60"));
        assertEquals(48, buffer.portions());
        assertEquals(Rule.BUFFER, buffer.rule());
    }
}
