package app.controller;

import app.dto.TrainingDataSourceResponse;
import app.service.TrainingDataSourceService;
import app.user.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/data-sources")
public class TrainingDataSourceController {

    private final TrainingDataSourceService service;

    public TrainingDataSourceController(TrainingDataSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<TrainingDataSourceResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TrainingDataSourceResponse upload(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam("file") MultipartFile file) {
        return service.upload(user.id(), file);
    }
}
