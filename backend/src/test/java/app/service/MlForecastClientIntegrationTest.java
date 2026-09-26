package app.service;

import app.dto.MlPredictionRequest;
import app.dto.MlPredictionResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MlForecastClientIntegrationTest {

    @Test
    void callsFastApiAndReturnsPrediction() {

        MlForecastClient client = new MlForecastClient();

        List<MlPredictionRequest.SalesRecord> history =
                new ArrayList<>();

        LocalDate startDate = LocalDate.of(2026, 8, 23);

        // 35 consecutive days of fake sales history.
        // Enough for:
        // - T1-T7
        // - 4 previous same-weekday observations
        for (int i = 0; i < 35; i++) {

            LocalDate date = startDate.plusDays(i);

            history.add(
                    new MlPredictionRequest.SalesRecord(
                            date.toString(),
                            20 + i
                    )
            );
        }

        MlPredictionRequest request =
                new MlPredictionRequest(
                        "CHICKEN",
                        "2026-09-27",
                        history,
                        new MlPredictionRequest.WeatherInput(
                                5.0,
                                4.0,
                                1.0,
                                4.0,
                                30.0
                        ),
                        false
                );

        MlPredictionResponse response =
                client.predict(request);

        assertNotNull(response);

        assertEquals(
                "CHICKEN",
                response.dish()
        );

        assertEquals(
                "2026-09-27",
                response.forecast_date()
        );

        assertTrue(
                Double.isFinite(
                        response.predicted_demand()
                )
        );

        System.out.println(
                "Prediction: "
                + response.predicted_demand()
        );
    }
}