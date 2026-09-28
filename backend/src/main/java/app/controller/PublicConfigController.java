package app.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import app.dto.PublicConfigResponse;

@Tag(name = "System", description = "Application configuration and security utilities")
@RestController
@RequestMapping("/api/config")
public class PublicConfigController {

    private final String googleClientId;

    public PublicConfigController(@Value("${google.client-id}") String googleClientId) {
        this.googleClientId = googleClientId;
    }
    
    @Operation(summary = "Get public configuration", description = "Returns public configuration values required by the frontend.")
    @ApiResponse(responseCode = "200", description = "Public configuration retrieved successfully")    
    @GetMapping
    public PublicConfigResponse publicConfig() {
        // OAuth client IDs are public identifiers; no client secret is exposed here.
        return new PublicConfigResponse(googleClientId);
    }
}
