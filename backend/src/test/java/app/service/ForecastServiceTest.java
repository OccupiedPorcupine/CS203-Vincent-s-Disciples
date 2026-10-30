package app.service;

import app.dish.Dish;
import app.dto.ForecastResponse;
import app.dto.ForecastResponse.FallbackReason;
import app.dto.ForecastResponse.Method;
import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import app.exception.InvalidForecastDateException;
import app.sale.DailySale;
import app.user.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForecastServiceTest {

    // Saturday 31 October 2026, morning in Singapore.
    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final MlPredictionRequest.WeatherInput WEATHER = new MlPredictionRequest.WeatherInput(2, 50, 0, 6, 29);

    private final ForecastDataService data = mock(ForecastDataService.class);
    private final NeaWeatherForecastService weather = mock(NeaWeatherForecastService.class);
    private final PublicHolidayService holidays = mock(PublicHolidayService.class);
    private final MlForecastClient ml = mock(MlForecastClient.class);
    private final Dish dish = new Dish("Chicken rice", AppUser.localUser("owner@example.com", "{pbkdf2}hash", "Owner"));

    private ForecastService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(9, 0).atZone(SINGAPORE).toInstant(), SINGAPORE);
        service = new ForecastService(data, weather, holidays, ml, clock);
        when(data.getOwnedDish(1L, 7L)).thenReturn(dish);
    }

    @Test
    void rejectsPastDatesAndDatesMoreThanAWeekAhead() {
        assertThrows(InvalidForecastDateException.class, () -> service.forecast(1L, TODAY.minusDays(1), 7L));
        assertThrows(InvalidForecastDateException.class, () -> service.forecast(1L, TODAY.plusDays(8), 7L));
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
        verify(ml, never()).knownDishes();
    }

    @Test
    void dishTheModelWasNotTrainedOnUsesTheBaseline() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 40));
        when(ml.knownDishes()).thenReturn(Optional.of(Set.of("CALAMARI", "LAMB")));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(FallbackReason.DISH_NOT_IN_MODEL, forecast.fallbackReason());
        assertEquals("Average of your last 4 Sundays", forecast.basis());
        verify(ml, never()).predict(any());
    }

    @Test
    void dateOutsideTheWeatherForecastUsesTheBaseline() {
        LocalDate nextWeek = TODAY.plusDays(5);
        when(data.getSalesHistory(any(), eq(nextWeek))).thenReturn(daysOfSales(28, 40));
        when(ml.knownDishes()).thenReturn(Optional.of(Set.of("CHICKEN RICE")));
        when(weather.getWeather(nextWeek)).thenThrow(new InvalidForecastDateException("outside"));

        ForecastResponse forecast = service.forecast(1L, nextWeek, 7L);

        assertEquals(FallbackReason.WEATHER_UNAVAILABLE, forecast.fallbackReason());
        assertEquals("The weather forecast doesn't cover this date yet, so this uses your past sales only.", forecast.note());
    }

    @Test
    void unreachableModelServiceUsesTheBaseline() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 40));
        when(ml.knownDishes()).thenThrow(new ResourceAccessException("Connection refused"));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(Method.BASELINE, forecast.method());
        assertEquals(FallbackReason.MODEL_UNAVAILABLE, forecast.fallbackReason());
    }

    @Test
    void failedPredictionUsesTheBaseline() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 40));
        when(ml.knownDishes()).thenReturn(Optional.empty());
        when(weather.getWeather(TOMORROW)).thenReturn(WEATHER);
        when(ml.predict(any())).thenThrow(new ResourceAccessException("Read timed out"));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(FallbackReason.MODEL_UNAVAILABLE, forecast.fallbackReason());
    }

    @Test
    void usesTheModelWhenItCanAndNeverReturnsANegativeForecast() {
        when(data.getSalesHistory(any(), eq(TOMORROW))).thenReturn(daysOfSales(28, 40));
        when(ml.knownDishes()).thenReturn(Optional.of(Set.of("CHICKEN RICE")));
        when(weather.getWeather(TOMORROW)).thenReturn(WEATHER);
        when(ml.predict(any())).thenReturn(new MlPredictionResponse("Chicken rice", TOMORROW.toString(), -3.2));

        ForecastResponse forecast = service.forecast(1L, TOMORROW, 7L);

        assertEquals(Method.MODEL, forecast.method());
        assertNull(forecast.fallbackReason());
        assertNull(forecast.note());
        assertEquals(0, forecast.predictedDemand(), 1e-9);
        assertEquals(0, forecast.low());
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
