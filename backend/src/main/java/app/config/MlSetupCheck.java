package app.config;

import app.diagnostics.SetupCheck;
import app.service.MlForecastClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Without the ML service, forecasts fall back to the baseline, so the app works either way; this
// says which is happening (as does MlHealthIndicator, without the address).
@Component
public class MlSetupCheck implements SetupCheck {

    private final MlForecastClient mlClient;
    private final String baseUrl;

    public MlSetupCheck(MlForecastClient mlClient, @Value("${app.ml.base-url}") String baseUrl) {
        this.mlClient = mlClient;
        this.baseUrl = baseUrl;
    }

    @Override
    public String name() {
        return "ml";
    }

    @Override
    public Result check() {
        return mlClient.isUp()
                ? Result.ok("The ML service answers at " + baseUrl + ".")
                : Result.problem("The ML service doesn't answer at " + baseUrl + ", so forecasts use the baseline.");
    }
}
