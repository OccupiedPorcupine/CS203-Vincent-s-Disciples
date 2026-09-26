package app.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.dto.AuthResponse;
import app.dto.LoginRequest;
import app.dto.SignupRequest;
import app.user.AppUser;
import app.service.LocalAuthService;
import app.service.SessionAuthenticationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class LocalAuthController {

    private final LocalAuthService localAuthService;
    private final SessionAuthenticationService sessionAuthenticationService;

    public LocalAuthController(
            LocalAuthService localAuthService,
            SessionAuthenticationService sessionAuthenticationService
    ) {
        this.localAuthService = localAuthService;
        this.sessionAuthenticationService = sessionAuthenticationService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(
            @Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AppUser user = localAuthService.signup(request);
        sessionAuthenticationService.authenticate(user, httpRequest, httpResponse);
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AppUser user = localAuthService.login(request);
        sessionAuthenticationService.authenticate(user, httpRequest, httpResponse);
        return ResponseEntity.ok(AuthResponse.from(user));
    }
}
