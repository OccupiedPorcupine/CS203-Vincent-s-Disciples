package com.csd.salesforecast.api;

import com.csd.salesforecast.api.ApiDtos.SourceResponse;
import com.csd.salesforecast.service.DataSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/data-sources")
public class DataSourceController {
    private final DataSourceService service;
    public DataSourceController(DataSourceService service) { this.service = service; }

    @GetMapping public List<SourceResponse> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SourceResponse upload(@RequestPart("file") MultipartFile file) throws Exception { return service.upload(file); }
}
