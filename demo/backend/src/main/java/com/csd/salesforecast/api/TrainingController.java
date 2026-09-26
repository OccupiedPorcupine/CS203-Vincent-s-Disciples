package com.csd.salesforecast.api;

import com.csd.salesforecast.api.ApiDtos.TrainingRunResponse;
import com.csd.salesforecast.service.TrainingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/training-runs")
public class TrainingController {
    private final TrainingService service;
    public TrainingController(TrainingService service) { this.service = service; }
    @GetMapping public List<TrainingRunResponse> list() { return service.list(); }
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED) public TrainingRunResponse start() { return service.start(); }
}
