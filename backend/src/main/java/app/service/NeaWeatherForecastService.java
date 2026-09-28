package app.service;

import app.dto.MlPredictionRequest;
import app.exception.InvalidForecastDateException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class NeaWeatherForecastService {

    private final RestClient restClient;
    private final WeatherFeatureAdapter weatherFeatureAdapter;

    public NeaWeatherForecastService(WeatherFeatureAdapter weatherFeatureAdapter) {
        this.weatherFeatureAdapter = weatherFeatureAdapter;
        this.restClient = RestClient.builder().baseUrl("https://api-open.data.gov.sg").build();
    }

    public MlPredictionRequest.WeatherInput getWeather(LocalDate forecastDate) {
        ApiResponse response = restClient.get()
                .uri("/v2/real-time/api/twenty-four-hr-forecast")
                .retrieve()
                .body(ApiResponse.class);

        if (response == null || response.data() == null || response.data().records() == null || response.data().records().isEmpty()) {
            throw new IllegalStateException("NEA weather forecast unavailable");
        }

        WeatherRecord record = response.data().records().getFirst();
        General general = record.general();

        validateForecastDate(forecastDate, general.validPeriod());

        return weatherFeatureAdapter.adapt(
                general.temperature().low(),
                general.temperature().high(),
                general.wind().speed().low(),
                general.wind().speed().high(),
                general.forecast().code()
        );
    }

    private void validateForecastDate(LocalDate forecastDate, ValidPeriod validPeriod) {
        LocalDate start = OffsetDateTime.parse(validPeriod.start()).toLocalDate();
        LocalDate end = OffsetDateTime.parse(validPeriod.end()).toLocalDate();

        if (forecastDate.isBefore(start) || forecastDate.isAfter(end)) {
            throw new InvalidForecastDateException("Forecast date is outside the NEA 24-hour forecast period");
        }
    }

    private record ApiResponse(Data data) {}
    private record Data(List<WeatherRecord> records) {}
    private record WeatherRecord(General general) {}
    private record General(Forecast forecast, Wind wind, Temperature temperature, ValidPeriod validPeriod) {}
    private record Forecast(String text, String code) {}
    private record Wind(String direction, Speed speed) {}
    private record Speed(double high, double low) {}
    private record Temperature(double high, double low, String unit) {}
    private record ValidPeriod(String start, String end) {}
}