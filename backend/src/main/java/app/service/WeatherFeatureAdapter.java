package app.service;

import app.dto.MlPredictionRequest;
import org.springframework.stereotype.Component;

@Component
public class WeatherFeatureAdapter {

    public MlPredictionRequest.WeatherInput adapt(double tempLow, double tempHigh, double windLow, double windHigh, String forecastCode) {
        double temperature = (tempLow + tempHigh) / 2.0;
        double wind = ((windLow + windHigh) / 2.0) / 3.6;
        double cloudCover = deriveCloudCover(forecastCode);
        double precipitation = derivePrecipitation(forecastCode);
        double sunshine = 720.0 * (1.0 - cloudCover / 8.0);

        return new MlPredictionRequest.WeatherInput(wind, cloudCover, precipitation, sunshine, temperature);
    }

    private double deriveCloudCover(String code) {
        return switch (code) {
            case "FA", "FN", "FW" -> 1.0;
            case "PC", "PN" -> 3.5;
            case "CL" -> 6.0;
            case "PS", "SH", "LR", "RA" -> 7.0;
            case "TL", "HR" -> 8.0;
            default -> 5.0;
        };
    }

    private double derivePrecipitation(String code) {
        return switch (code) {
            case "FA", "FN", "FW", "PC", "PN", "CL", "WD" -> 0.0;
            case "LR", "PS" -> 0.3;
            case "SH", "RA" -> 1.0;
            case "TL" -> 3.3;
            case "HR" -> 6.3;
            default -> 0.0;
        };
    }
}