package app.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.dto.PublicConfigResponse;

@RestController
@RequestMapping("/api/config")
public class PublicConfigController {

    private final String googleClientId;

    public PublicConfigController(@Value("${google.client-id}") String googleClientId) {
        this.googleClientId = googleClientId;
    }

    @GetMapping
    public PublicConfigResponse publicConfig() {
        // OAuth client IDs are public identifiers; no client secret is exposed here.
        return new PublicConfigResponse(googleClientId);
    }
}
