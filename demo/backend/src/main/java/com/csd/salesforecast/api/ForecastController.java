package com.csd.salesforecast.api;

import com.csd.salesforecast.api.ApiDtos.ForecastResponse;
import com.csd.salesforecast.service.ForecastService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/forecasts")
public class ForecastController {
    private final ForecastService service;
    public ForecastController(ForecastService service) { this.service = service; }

    @GetMapping("/tomorrow")
    public ForecastResponse tomorrow(@RequestParam(defaultValue="Cloudy") String weather,
        @RequestParam(defaultValue="false") boolean holiday,
        @RequestParam(defaultValue="true") boolean stallOpen) {
        return service.tomorrow(weather, holiday, stallOpen);
    }
}
