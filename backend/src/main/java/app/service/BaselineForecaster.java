package app.service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// The fallback forecast: roughly what a stall owner would guess from their own records. It is
// used when the model can't give a meaningful answer (too little history, a dish the model wasn't
// trained on, no weather forecast, or the ML service is down). Its errors on recent days also set
// the likely range shown with every forecast, including the model's.
final class BaselineForecaster {

    static final int SAME_WEEKDAYS = 4;
    static final int RECENT_DAYS = 7;
    // How many recent days are re-forecast to measure the baseline's typical error.
    static final int BACKTEST_DAYS = 12;
    // The margin rule (PrepRule.MARGIN) aims at a percentile, which 12 errors are too few to place:
    // in the prep simulator (mean of 4 runs) it earned 77.3% of the best possible profit with them
    // and 82.4% with 8 weeks, ahead of the top of the 12-day range (82.1%). The shown range keeps
    // 12 days, because widening it made the top of the range over-prepare (80.9%). See ml/README.md, #86.
    static final int MARGIN_BACKTEST_DAYS = 56;
    static final int MIN_BACKTEST_ERRORS = 5;
    // Used as +/- 25% when there are too few past errors to measure the range.
    static final double ROUGH_SPREAD = 0.25;

    record Observation(LocalDate date, double quantity) {}

    record Estimate(double quantity, String basis) {}

    record Range(double low, double high, boolean rough) {}

    private BaselineForecaster() {}

    // history: oldest first, all before the date. Empty when there is no history at all.
    static Optional<Estimate> estimate(List<Observation> history, LocalDate date) {
        List<Observation> sameWeekday = history.stream()
                .filter(observation -> observation.date().getDayOfWeek() == date.getDayOfWeek())
                .toList();

        if (!sameWeekday.isEmpty()) {
            List<Observation> latest = last(sameWeekday, SAME_WEEKDAYS);
            String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            String basis = latest.size() == 1
                    ? "Your last " + weekday
                    : "Average of your last " + latest.size() + " " + weekday + "s";
            return Optional.of(new Estimate(mean(latest), basis));
        }

        if (history.isEmpty()) {
            return Optional.empty();
        }

        List<Observation> recent = last(history, RECENT_DAYS);
        String basis = recent.size() == 1
                ? "Your only recorded day of sales"
                : "Average of your last " + recent.size() + " days of sales";
        return Optional.of(new Estimate(mean(recent), basis));
    }

    // The likely range around a forecast, from how far the baseline was off on recent days.
    static Range range(List<Observation> history, double forecast) {
        List<Double> errors = errors(history, BACKTEST_DAYS);

        double low;
        double high;
        boolean rough = errors.size() < MIN_BACKTEST_ERRORS;
        if (rough) {
            low = forecast * (1 - ROUGH_SPREAD);
            high = forecast * (1 + ROUGH_SPREAD);
        } else {
            errors.sort(null);
            low = forecast + quantile(errors, 0.1);
            high = forecast + quantile(errors, 0.9);
        }
        return new Range(Math.max(0, Math.min(low, forecast)), Math.max(high, forecast), rough);
    }

    // The quantity demand stays at or below with the given probability, from the baseline's errors
    // over the last MARGIN_BACKTEST_DAYS days. With too few errors it follows the rough range,
    // so 0.1 and 0.9 give the same -25% and +25% as range(). Never below zero.
    static double quantile(List<Observation> history, double forecast, double probability) {
        List<Double> errors = errors(history, MARGIN_BACKTEST_DAYS);
        if (errors.size() < MIN_BACKTEST_ERRORS) {
            return Math.max(0, forecast * (1 + ROUGH_SPREAD * (probability - 0.5) / 0.4));
        }
        errors.sort(null);
        return Math.max(0, forecast + quantile(errors, probability));
    }

    // Actual minus estimate on each of the last `days` recorded days, sorted later by the caller.
    private static List<Double> errors(List<Observation> history, int days) {
        List<Double> errors = new ArrayList<>();
        for (int index = Math.max(1, history.size() - days); index < history.size(); index++) {
            Observation actual = history.get(index);
            estimate(history.subList(0, index), actual.date())
                    .ifPresent(estimate -> errors.add(actual.quantity() - estimate.quantity()));
        }
        return errors;
    }

    static long sameWeekdayCount(List<Observation> history, LocalDate date) {
        return history.stream().filter(observation -> observation.date().getDayOfWeek() == date.getDayOfWeek()).count();
    }

    private static List<Observation> last(List<Observation> observations, int count) {
        return observations.subList(Math.max(0, observations.size() - count), observations.size());
    }

    private static double mean(List<Observation> observations) {
        return observations.stream().mapToDouble(Observation::quantity).average().orElseThrow();
    }

    // Linear interpolation between the closest ranks; sorted must be in ascending order.
    private static double quantile(List<Double> sorted, double probability) {
        double position = probability * (sorted.size() - 1);
        int below = (int) Math.floor(position);
        int above = (int) Math.ceil(position);
        return sorted.get(below) + (sorted.get(above) - sorted.get(below)) * (position - below);
    }
}
