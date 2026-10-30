package app.service;

import app.service.BaselineForecaster.Estimate;
import app.service.BaselineForecaster.Observation;
import app.service.BaselineForecaster.Range;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineForecasterTest {

    // A Saturday.
    private static final LocalDate TARGET = LocalDate.of(2026, 10, 31);

    @Test
    void averagesTheLastFourSameWeekdays() {
        List<Observation> history = new ArrayList<>();
        int[] saturdays = {10, 20, 30, 40, 50};
        for (int week = 5; week >= 1; week--) {
            history.add(new Observation(TARGET.minusWeeks(week), saturdays[5 - week]));
            history.add(new Observation(TARGET.minusWeeks(week).plusDays(1), 99));
        }

        Estimate estimate = BaselineForecaster.estimate(history, TARGET).orElseThrow();

        assertEquals(35, estimate.quantity(), 1e-9);
        assertEquals("Average of your last 4 Saturdays", estimate.basis());
    }

    @Test
    void usesTheOnlySameWeekdayWhenThereIsOne() {
        List<Observation> history = List.of(
                new Observation(TARGET.minusDays(7), 42),
                new Observation(TARGET.minusDays(1), 10));

        Estimate estimate = BaselineForecaster.estimate(history, TARGET).orElseThrow();

        assertEquals(42, estimate.quantity(), 1e-9);
        assertEquals("Your last Saturday", estimate.basis());
    }

    @Test
    void averagesRecentDaysWhenThereIsNoSameWeekday() {
        List<Observation> history = List.of(
                new Observation(TARGET.minusDays(3), 10),
                new Observation(TARGET.minusDays(2), 20),
                new Observation(TARGET.minusDays(1), 30));

        Estimate estimate = BaselineForecaster.estimate(history, TARGET).orElseThrow();

        assertEquals(20, estimate.quantity(), 1e-9);
        assertEquals("Average of your last 3 days of sales", estimate.basis());
    }

    @Test
    void noHistoryGivesNoEstimate() {
        assertEquals(Optional.empty(), BaselineForecaster.estimate(List.of(), TARGET));
    }

    @Test
    void fewPastDaysGiveARoughRange() {
        List<Observation> history = List.of(
                new Observation(TARGET.minusDays(2), 40),
                new Observation(TARGET.minusDays(1), 40));

        Range range = BaselineForecaster.range(history, 40);

        assertTrue(range.rough());
        assertEquals(30, range.low(), 1e-9);
        assertEquals(50, range.high(), 1e-9);
    }

    @Test
    void steadySalesGiveATightRange() {
        List<Observation> history = new ArrayList<>();
        for (int day = 28; day >= 1; day--) {
            history.add(new Observation(TARGET.minusDays(day), 40));
        }

        Range range = BaselineForecaster.range(history, 40);

        assertFalse(range.rough());
        assertEquals(40, range.low(), 1e-9);
        assertEquals(40, range.high(), 1e-9);
    }

    @Test
    void rangeContainsTheForecastAndIsNeverNegative() {
        List<Observation> history = new ArrayList<>();
        for (int day = 28; day >= 1; day--) {
            history.add(new Observation(TARGET.minusDays(day), day % 2 == 0 ? 2 : 30));
        }

        Range range = BaselineForecaster.range(history, 3);

        assertFalse(range.rough());
        assertEquals(0, range.low(), 1e-9);
        assertTrue(range.high() >= 3);
    }
}
