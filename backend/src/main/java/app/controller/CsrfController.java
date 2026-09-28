package app.controller;

import java.util.Map;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "System", description = "Application configuration and security utilities")
@RestController
@RequestMapping("/api/csrf")
public class CsrfController {
    
    @Operation(summary = "Get CSRF token", description = "Returns the CSRF token and associated header and parameter names required for protected requests.")
    @ApiResponse(responseCode = "200", description = "CSRF token retrieved successfully")
    @GetMapping
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName()
        );
    }
}
