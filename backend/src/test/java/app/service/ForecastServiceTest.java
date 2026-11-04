package app.service;

import app.dish.Dish;
import app.dto.ForecastResponse;
import app.dto.ForecastResponse.FallbackReason;
import app.dto.ForecastResponse.Method;
import app.dto.MlForecastRequest;
import app.dto.MlForecastResponse;
import app.exception.InvalidForecastDateException;
import app.exception.StallClosedException;
import app.sale.DailySale;
import app.user.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastServiceTest {

    // Saturday 31 October 2026, morning in Singapore.
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final List<Double> LEVELS = List.of(0.1, 0.5, 0.9);

    private final ForecastDataService data = mock(ForecastDataService.class);
    private final PublicHolidayService holidays = mock(PublicHolidayService.class);
    private final MlForecastClient ml = mock(MlForecastClient.class);
    private final StallClosureService closures = mock(StallClosureService.class);
    private final Dish dish = new Dish("Chicken rice", AppUser.localUser("owner@example.com", "{pbkdf2}hash", "Owner"));

    private ForecastService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(SINGAPORE).toInstant(), SINGAPORE);
        service = new ForecastService(data, holidays, ml, closures, clock, false);
        when(data.getOwnedDish(1L, 7L)).thenReturn(dish);
    }

    @Test
    void rejectsPastDatesAndDatesMoreThanAWeekAhead() {
        assertThrows(InvalidForecastDateException.class, () -> service.forecast(1L, TODAY.minusDays(1), 7L));
        assertThrows(InvalidForecastDateException.class, () -> service.forecast(1L, TODAY.plusDays(8), 7L));
    }

    @Test
    void closedDaysAreNotForecast() {
        when(closures.closureMessage(7L, TOMORROW)).thenReturn(Optional.of("Your stall is closed on Sundays."));

        StallClosedException error = assertThrows(StallClosedException.class, () -> service.forecast(1L, TOMORROW, 7L));

        assertEquals("Your stall is closed on Sundays.", error.getMessage());
        verify(data, never()).getSalesHistory(any(), any());
    }

    @Test
    void explainsThatSalesAreNeededFirst() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.forecast(1L, TOMORROW, 7L));

        assertTrue(error.getMessage().startsWith("No sales are recorded for Chicken rice yet"));
    }

    @Test
    void shortHistoryUsesTheBaselineWithoutCallingTheModel() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(5, 40));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(Method.BASELINE, forecast.method());
        assertEquals(FallbackReason.SHORT_HISTORY, forecast.fallbackReason());
        assertEquals(40, forecast.predictedDemand(), 1e-9);
        assertTrue(forecast.roughRange());
        assertEquals("The forecast model needs at least 7 days of sales, including 4 Sundays. "
                + "This dish has 5 days so far, with 0 Sundays.", forecast.note());
        verify(ml, never()).forecast(any());
    }

    @Test
    void unreachableModelServiceUsesTheBaseline() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 40));
        when(ml.forecast(any())).thenThrow(new ResourceAccessException("Read timed out"));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(Method.BASELINE, forecast.method());
        assertEquals(FallbackReason.MODEL_UNAVAILABLE, forecast.fallbackReason());
        assertEquals("Average of your last 4 Sundays", forecast.basis());
        assertEquals("The forecast model isn't responding, so this uses your past sales only.", forecast.note());
    }

    @Test
    void theModelsQuantilesGiveTheForecastTheRangeAndThePrepListsShareOfDays() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 40));
        when(ml.forecast(any())).thenReturn(model(30.4, 42, 51.6));

        ForecastService.Prediction prediction = service.predict(1L, TOMORROW, 7L);
        ForecastResponse forecast = prediction.response();

        assertEquals(Method.MODEL, forecast.method());
        assertNull(forecast.fallbackReason());
        assertNull(forecast.note());
        assertEquals(42, forecast.predictedDemand(), 1e-9);
        assertEquals(30, forecast.low());
        assertEquals(52, forecast.high());
        assertFalse(forecast.roughRange());
        assertEquals("Forecast model (Chronos-2), from your daily sales and public holidays", forecast.basis());
        // Between the quantiles it interpolates; outside them it keeps to the nearest.
        assertEquals(46.8, prediction.atProbability(0.7), 1e-9);
        assertEquals(51.6, prediction.atProbability(0.95), 1e-9);
    }

    @Test
    void negativeQuantilesCountAsNoSales() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 1));
        when(ml.forecast(any())).thenReturn(model(-2, -0.5, 3));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(0, forecast.predictedDemand(), 1e-9);
        assertEquals(0, forecast.low());
        assertEquals(3, forecast.high());
    }

    @Test
    void theModelGetsAYearOfSalesAndThePublicHolidays() {
        List<DailySale> twoYears = daysOfSales(730, 40);
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(twoYears);
        LocalDate nationalDay = LocalDate.of(2026, 8, 9);
        when(holidays.holidaysBetween(TOMORROW.minusDays(365), TOMORROW)).thenReturn(List.of(nationalDay));
        when(ml.forecast(any())).thenReturn(model(35, 40, 45));

        service.forecast(1L, TOMORROW, 7L);

        ArgumentCaptor<MlForecastRequest> sent = ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(ml).forecast(sent.capture());
        assertEquals(TOMORROW.toString(), sent.getValue().forecast_date());
        assertEquals(365, sent.getValue().sales_history().size()); // the 365 days before tomorrow
        assertEquals(TOMORROW.minusDays(365).toString(), sent.getValue().sales_history().getFirst().date());
        assertEquals(TODAY.toString(), sent.getValue().sales_history().getLast().date());
        assertEquals(List.of("2026-08-09"), sent.getValue().holidays());
    }

    @Test
    void theModelCountsSoldOutDaysAsHigherDemandButTheBaselineDoesNot() {
        List<DailySale> sales = daysOfSales(28, 40);
        // Sold out at 2 pm in an 8 am to 8 pm day: half the day on sale, so 1.5 times (the cap).
        sales.getLast().setOutcome(LocalTime.of(14, 0), null);
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(sales);
        when(ml.forecast(any())).thenReturn(model(35, 40, 45)).thenThrow(new ResourceAccessException("down"));

        ForecastResponse model = service.forecast(1L, TOMORROW, 7L);
        ForecastResponse baseline = service.forecast(1L, TOMORROW, 7L);

        ArgumentCaptor<MlForecastRequest> sent = ArgumentCaptor.forClass(MlForecastRequest.class);
        verify(ml, times(2)).forecast(sent.capture());
        assertEquals(60, sent.getAllValues().getFirst().sales_history().getLast().demand(), 1e-9);
        assertEquals("Forecast model (Chronos-2), from your daily sales and public holidays, counting 1 sold-out day "
                + "as higher demand", model.basis());
        assertEquals(40, baseline.predictedDemand(), 1e-9);
        assertEquals("Average of your last 4 Sundays", baseline.basis());
    }

    @Test
    void theRangeWidensWhenTheLatestSalesAreOld() {
        // Sales rising by one a day for six weeks, so older sales are further off.
        List<DailySale> rising = new ArrayList<>();
        for (int day = 42; day >= 1; day--) {
            rising.add(new DailySale(dish, TOMORROW.minusDays(day), 100 - day));
        }
        LocalDate nextWeek = TODAY.plusDays(7);
        List<DailySale> stale = rising.subList(0, rising.size() - 6);
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(rising);
        when(data.getSalesHistory(any(), eq(nextWeek))).thenReturn(stale);
        when(ml.forecast(any())).thenThrow(new ResourceAccessException("down"));

        ForecastResponse fresh = service.forecast(1L, TOMORROW, 7L);
        ForecastResponse old = service.forecast(1L, nextWeek, 7L);

        // The last sale is 1 day before tomorrow, but 13 days before next week.
        assertTrue(old.high() - old.predictedDemand() > fresh.high() - fresh.predictedDemand(),
                "fresh " + fresh.low() + "-" + fresh.high() + " around " + fresh.predictedDemand()
                        + ", old " + old.low() + "-" + old.high() + " around " + old.predictedDemand());
    }

    // The model's answer at the 10%, 50% and 90% quantiles.
    private static MlForecastResponse model(double low, double median, double high) {
        return new MlForecastResponse(TOMORROW.toString(), "chronos-2", LEVELS, List.of(low, median, high), median);
    }

    // One sale a day for the given number of days before tomorrow, all the same quantity.
    private List<DailySale> daysOfSales(int days, int quantity) {
        List<DailySale> sales = new ArrayList<>();
        for (int day = days; day >= 1; day--) {
            sales.add(new DailySale(dish, TOMORROW.minusDays(day), quantity));
        }
        return sales;
    }
}
