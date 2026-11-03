package app.service;

import app.service.BaselineForecaster.Estimate;
import app.service.BaselineForecaster.Observation;
import app.service.BaselineForecaster.Range;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

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

    @Test
    void theMarginQuantileMeasuresEightWeeksOfErrors() {
        Random random = new Random(7);
        List<Observation> history = new ArrayList<>();
        for (int daysAgo = 100; daysAgo >= 1; daysAgo--) {
            history.add(new Observation(TARGET.minusDays(daysAgo), 30 + random.nextInt(30)));
        }

        // The same thing written out directly: re-forecast each of the last 56 days from the days
        // before it (the mean of up to 4 same weekdays), and take the 70th percentile of the errors.
        List<Double> errors = new ArrayList<>();
        for (int index = history.size() - 56; index < history.size(); index++) {
            List<Double> sameWeekday = new ArrayList<>();
            for (int earlier = index - 7; earlier >= 0 && sameWeekday.size() < 4; earlier -= 7) {
                sameWeekday.add(history.get(earlier).quantity());
            }
            double estimate = sameWeekday.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            errors.add(history.get(index).quantity() - estimate);
        }
        errors.sort(null);
        double position = 0.7 * (errors.size() - 1);
        int below = (int) Math.floor(position);
        double expected = 40 + errors.get(below) + (errors.get(below + 1) - errors.get(below)) * (position - below);

        assertEquals(expected, BaselineForecaster.quantile(history, 40, 0.7), 1e-9);
    }

    @Test
    void theMarginQuantileFollowsTheRoughRangeWithFewErrorsAndIsNeverNegative() {
        List<Observation> history = List.of(
                new Observation(TARGET.minusDays(3), 30),
                new Observation(TARGET.minusDays(2), 40),
                new Observation(TARGET.minusDays(1), 50));

        assertEquals(50, BaselineForecaster.quantile(history, 40, 0.9), 1e-9);
        assertEquals(30, BaselineForecaster.quantile(history, 40, 0.1), 1e-9);
        assertEquals(40, BaselineForecaster.quantile(history, 40, 0.5), 1e-9);

        List<Observation> falling = new ArrayList<>();
        for (int daysAgo = 70; daysAgo >= 1; daysAgo--) {
            falling.add(new Observation(TARGET.minusDays(daysAgo), daysAgo));
        }
        assertEquals(0, BaselineForecaster.quantile(falling, 1, 0.1), 1e-9);
    }
}
