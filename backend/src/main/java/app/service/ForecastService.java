package app.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.dish.Dish;
import app.dto.ForecastResponse;
import app.dto.ForecastResponse.FallbackReason;
import app.dto.ForecastResponse.Method;
import app.dto.MlPredictionRequest;
import app.exception.InvalidForecastDateException;
import app.sale.DailySale;
import app.service.BaselineForecaster.Estimate;
import app.service.BaselineForecaster.Observation;
import app.service.BaselineForecaster.Range;

// Forecasts one dish for one day. The trained model is used only when it can give a meaningful
// answer; otherwise the dish's own past sales are averaged (BaselineForecaster), and the response
// says which was used and why, so the user always gets a forecast they can understand.
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    static final int MAX_DAYS_AHEAD = 7;
    // The model's features need 7 earlier days of sales and 4 earlier days on the same weekday.
    static final int MODEL_MIN_DAYS = 7;
    static final int MODEL_MIN_SAME_WEEKDAYS = 4;

    private static final String MODEL_BASIS = "Forecast model, using your recent sales, the weather forecast and public holidays";

    private final ForecastDataService forecastDataService;
    private final NeaWeatherForecastService neaWeatherForecastService;
    private final PublicHolidayService publicHolidayService;
    private final MlForecastClient mlForecastClient;
    private final Clock clock;

    public ForecastService(ForecastDataService forecastDataService, NeaWeatherForecastService neaWeatherForecastService, PublicHolidayService publicHolidayService,
                           MlForecastClient mlForecastClient, Clock clock) {
        this.forecastDataService = forecastDataService;
        this.neaWeatherForecastService = neaWeatherForecastService;
        this.publicHolidayService = publicHolidayService;
        this.mlForecastClient = mlForecastClient;
        this.clock = clock;
    }

    public ForecastResponse forecast(Long dishId, LocalDate date, Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new InvalidForecastDateException("Choose a date from today up to " + MAX_DAYS_AHEAD + " days ahead.");
        }

        Dish dish = forecastDataService.getOwnedDish(dishId, ownerId);
        List<DailySale> sales = forecastDataService.getSalesHistory(dish.getId(), date);
        List<Observation> history = sales.stream()
                .map(sale -> new Observation(sale.getSaleDate(), sale.getQuantitySold()))
                .toList();

        Estimate baseline = BaselineForecaster.estimate(history, date).orElseThrow(() -> new IllegalArgumentException(
                "No sales are recorded for " + dish.getName() + " yet. Record a few days of sales to get a forecast."));

        long sameWeekdays = BaselineForecaster.sameWeekdayCount(history, date);
        if (history.size() < MODEL_MIN_DAYS || sameWeekdays < MODEL_MIN_SAME_WEEKDAYS) {
            String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            return fromBaseline(dish, date, history, baseline, FallbackReason.SHORT_HISTORY,
                    "The forecast model needs at least " + MODEL_MIN_DAYS + " days of sales, including "
                            + MODEL_MIN_SAME_WEEKDAYS + " " + weekday + "s. This dish has " + days(history.size())
                            + " so far, with " + sameWeekdays + " " + weekday + (sameWeekdays == 1 ? "" : "s") + ".");
        }

        try {
            Optional<Set<String>> modelDishes = mlForecastClient.knownDishes();
            if (modelDishes.isPresent() && !modelDishes.get().contains(MlForecastClient.normalise(dish.getName()))) {
                return fromBaseline(dish, date, history, baseline, FallbackReason.DISH_NOT_IN_MODEL,
                        "The forecast model hasn't been trained on this dish yet, so this uses your past sales only.");
            }
        } catch (RuntimeException exception) {
            return modelUnavailable(dish, date, history, baseline, exception);
        }

        MlPredictionRequest.WeatherInput weather;
        try {
            weather = neaWeatherForecastService.getWeather(date);
        } catch (InvalidForecastDateException exception) {
            return fromBaseline(dish, date, history, baseline, FallbackReason.WEATHER_UNAVAILABLE,
                    "The weather forecast doesn't cover this date yet, so this uses your past sales only.");
        } catch (RuntimeException exception) {
            log.warn("NEA weather unavailable, using the baseline forecast: {}", exception.toString());
            return fromBaseline(dish, date, history, baseline, FallbackReason.WEATHER_UNAVAILABLE,
                    "The weather service isn't responding, so this uses your past sales only.");
        }

        double prediction;
        try {
            prediction = mlForecastClient.predict(new MlPredictionRequest(
                    dish.getName(),
                    date.toString(),
                    sales.stream().map(sale -> new MlPredictionRequest.SalesRecord(
                            sale.getSaleDate().toString(), sale.getQuantitySold())).toList(),
                    weather,
                    publicHolidayService.isPublicHoliday(date)
            )).predicted_demand();
        } catch (RuntimeException exception) {
            return modelUnavailable(dish, date, history, baseline, exception);
        }

        double expected = Math.max(0, prediction);
        return response(dish, date, history, expected, Method.MODEL, null, MODEL_BASIS, null);
    }

    private ForecastResponse modelUnavailable(Dish dish, LocalDate date, List<Observation> history, Estimate baseline,
                                              RuntimeException exception) {
        log.warn("ML service unavailable, using the baseline forecast: {}", exception.toString());
        return fromBaseline(dish, date, history, baseline, FallbackReason.MODEL_UNAVAILABLE,
                "The forecast model isn't responding, so this uses your past sales only.");
    }

    private ForecastResponse fromBaseline(Dish dish, LocalDate date, List<Observation> history, Estimate baseline,
                                          FallbackReason reason, String note) {
        return response(dish, date, history, baseline.quantity(), Method.BASELINE, reason, baseline.basis(), note);
    }

    private ForecastResponse response(Dish dish, LocalDate date, List<Observation> history, double expected,
                                      Method method, FallbackReason reason, String basis, String note) {
        Range range = BaselineForecaster.range(history, expected);
        return new ForecastResponse(dish.getName(), date, expected,
                (int) Math.floor(range.low()), (int) Math.ceil(range.high()), range.rough(),
                method, reason, basis, note);
    }

    private static String days(int count) {
        return count == 1 ? "1 day" : count + " days";
    }
}
