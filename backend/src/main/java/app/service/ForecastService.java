package app.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.closure.Hours;
import app.dish.Dish;
import app.dto.ForecastResponse;
import app.dto.ForecastResponse.FallbackReason;
import app.dto.ForecastResponse.Method;
import app.dto.MlForecastRequest;
import app.dto.MlForecastResponse;
import app.exception.InvalidForecastDateException;
import app.exception.StallClosedException;
import app.sale.DailySale;
import app.service.BaselineForecaster.Estimate;
import app.service.BaselineForecaster.Observation;
import app.service.BaselineForecaster.Range;

// Forecasts one dish for one day. Chronos-2, a pretrained forecasting model in the ML service, is
// used for every dish with a few weeks of sales (issue #106); otherwise the dish's own past sales
// are averaged (BaselineForecaster), and the response says which was used and why, so the user
// always gets a forecast they can understand.
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    static final int MAX_DAYS_AHEAD = 7;
    // With less than this the model has too little of the weekly pattern to go on: at least 7 days
    // of sales, 4 of them on the forecast's weekday.
    static final int MODEL_MIN_DAYS = 7;
    static final int MODEL_MIN_SAME_WEEKDAYS = 4;
    // The model is sent this many days of sales before the forecast date, as in its benchmark (#87).
    static final int MODEL_CONTEXT_DAYS = 365;
    // The basis mentions sold-out days in this many days before the forecast date.
    static final int SOLD_OUT_NOTE_DAYS = 28;
    // The baseline's range is measured on estimates made up to this many days ahead.
    static final int MAX_HORIZON_DAYS = 28;


    private final ForecastDataService forecastDataService;
    private final PublicHolidayService publicHolidayService;
    private final MlForecastClient mlForecastClient;
    private final StallClosureService stallClosureService;
    private final Clock clock;
    // Whether the baseline counts sold-out days as higher demand. Off: with the baseline's ranges it
    // doesn't help the prep list's margin rule (#86); see SoldOutDemand. The model always does,
    // which is what helped most in its benchmark (#87).
    private final boolean countSoldOutDemand;

    public ForecastService(ForecastDataService forecastDataService, PublicHolidayService publicHolidayService,
                           MlForecastClient mlForecastClient, StallClosureService stallClosureService, Clock clock,
                           @Value("${app.forecast.count-sold-out-demand:false}") boolean countSoldOutDemand) {
        this.forecastDataService = forecastDataService;
        this.publicHolidayService = publicHolidayService;
        this.mlForecastClient = mlForecastClient;
        this.stallClosureService = stallClosureService;
        this.clock = clock;
        this.countSoldOutDemand = countSoldOutDemand;
    }

    // A forecast, and the demand reached on a given share of days like it, so the prep list can aim
    // at a point set by the dish's margin (#86): from the model's quantiles, or from the baseline's
    // past errors (BaselineForecaster.atProbability).
    record Prediction(ForecastResponse response, DoubleUnaryOperator demandAt) {
        double atProbability(double probability) {
            return demandAt.applyAsDouble(probability);
        }
    }

    public ForecastResponse forecast(Long dishId, LocalDate date, Long ownerId) {
        return predict(dishId, date, ownerId).response();
    }

    Prediction predict(Long dishId, LocalDate date, Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new InvalidForecastDateException("Choose a date from today up to " + MAX_DAYS_AHEAD + " days ahead.");
        }

        Dish dish = forecastDataService.getOwnedDish(dishId, ownerId);
        stallClosureService.closureMessage(ownerId, date).ifPresent(message -> {
            throw new StallClosedException(message);
        });
        List<DailySale> sales = forecastDataService.getSalesHistory(dish.getId(), date);
        List<Observation> recorded = sales.stream()
                .map(sale -> new Observation(sale.getSaleDate(), sale.getQuantitySold()))
                .toList();
        if (recorded.isEmpty()) {
            throw new IllegalArgumentException("No sales are recorded for " + dish.getName()
                    + " yet. Record a few days of sales to get a forecast.");
        }
        // Days from the last recorded sale to the date. The baseline's range is measured on
        // estimates made that far ahead, so it widens when the latest sales are old.
        int horizon = (int) Math.clamp(ChronoUnit.DAYS.between(recorded.getLast().date(), date), 1, MAX_HORIZON_DAYS);

        // Sold-out days count as the demand they likely had (SoldOutDemand). The baseline's range
        // still comes from the recorded sales: measured on the scaled-up days it widens, and the prep
        // list's top of the range would over-prepare (#50, #86).
        long soldOutDays = sales.stream()
                .filter(sale -> sale.getSoldOutAt() != null && !sale.getSaleDate().isBefore(date.minusDays(SOLD_OUT_NOTE_DAYS)))
                .count();
        List<Observation> demand = recorded;
        String soldOutNote = "";
        if (soldOutDays > 0) {
            Hours hours = stallClosureService.hours(ownerId).orElse(Hours.DEFAULT);
            demand = sales.stream()
                    .map(sale -> new Observation(sale.getSaleDate(),
                            SoldOutDemand.estimate(sale.getQuantitySold(), sale.getSoldOutAt(), hours)))
                    .toList();
            soldOutNote = ", counting " + (soldOutDays == 1 ? "1 sold-out day" : soldOutDays + " sold-out days")
                    + " as higher demand";
        }

        long sameWeekdays = BaselineForecaster.sameWeekdayCount(recorded, date);
        if (recorded.size() < MODEL_MIN_DAYS || sameWeekdays < MODEL_MIN_SAME_WEEKDAYS) {
            String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            return fromBaseline(dish, date, recorded, demand, soldOutNote, horizon, FallbackReason.SHORT_HISTORY,
                    "The forecast model needs at least " + MODEL_MIN_DAYS + " days of sales, including "
                            + MODEL_MIN_SAME_WEEKDAYS + " " + weekday + "s. This dish has " + days(recorded.size())
                            + " so far, with " + sameWeekdays + " " + weekday + (sameWeekdays == 1 ? "" : "s") + ".");
        }

        double[] levels;
        double[] quantiles;
        try {
            MlForecastResponse model = mlForecastClient.forecast(modelRequest(date, demand));
            levels = model.quantile_levels().stream().mapToDouble(Double::doubleValue).toArray();
            quantiles = model.quantiles().stream().mapToDouble(value -> Math.max(0, value)).sorted().toArray();
        } catch (RuntimeException exception) {
            log.warn("ML service unavailable, using the baseline forecast: {}", exception.toString());
            return fromBaseline(dish, date, recorded, demand, soldOutNote, horizon, FallbackReason.MODEL_UNAVAILABLE,
                    "The forecast model isn't responding, so this uses your past sales only.");
        }
        DoubleUnaryOperator demandAt = probability -> interpolate(levels, quantiles, probability);
        double expected = demandAt.applyAsDouble(0.5);
        double low = demandAt.applyAsDouble(0.1);
        double high = demandAt.applyAsDouble(0.9);
        String basis = "Forecast model (Chronos-2), from your daily sales and public holidays" + soldOutNote;
        return new Prediction(new ForecastResponse(dish.getName(), date, expected,
                (int) Math.floor(Math.min(low, expected)), (int) Math.ceil(Math.max(high, expected)), false,
                Method.MODEL, null, basis, null), demandAt);
    }

    private MlForecastRequest modelRequest(LocalDate date, List<Observation> demand) {
        LocalDate from = date.minusDays(MODEL_CONTEXT_DAYS);
        List<MlForecastRequest.SalesRecord> history = demand.stream()
                .filter(observation -> !observation.date().isBefore(from))
                .map(observation -> new MlForecastRequest.SalesRecord(observation.date().toString(), observation.quantity()))
                .toList();
        List<String> holidays = publicHolidayService.holidaysBetween(from, date).stream().map(LocalDate::toString).toList();
        return new MlForecastRequest(date.toString(), history, holidays);
    }

    private Prediction fromBaseline(Dish dish, LocalDate date, List<Observation> recorded, List<Observation> demand,
                                    String soldOutNote, int horizon, FallbackReason reason, String note) {
        boolean counted = countSoldOutDemand && !soldOutNote.isEmpty();
        Estimate baseline = BaselineForecaster.estimate(counted ? demand : recorded, date).orElseThrow();
        double expected = baseline.quantity();
        Range range = BaselineForecaster.range(recorded, expected, horizon);
        return new Prediction(new ForecastResponse(dish.getName(), date, expected,
                (int) Math.floor(range.low()), (int) Math.ceil(range.high()), range.rough(),
                Method.BASELINE, reason, baseline.basis() + (counted ? soldOutNote : ""), note),
                probability -> BaselineForecaster.atProbability(recorded, expected, probability, horizon));
    }

    // The value at a probability, between the model's quantiles; outside them, the nearest one.
    static double interpolate(double[] levels, double[] values, double probability) {
        if (probability <= levels[0]) return values[0];
        for (int index = 1; index < levels.length; index++) {
            if (probability <= levels[index]) {
                double share = (probability - levels[index - 1]) / (levels[index] - levels[index - 1]);
                return values[index - 1] + (values[index] - values[index - 1]) * share;
            }
        }
        return values[values.length - 1];
    }

    private static String days(int count) {
        return count == 1 ? "1 day" : count + " days";
    }
}
