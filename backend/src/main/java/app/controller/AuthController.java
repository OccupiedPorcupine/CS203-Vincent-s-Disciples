package app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.dto.AuthResponse;
import app.dto.GoogleLoginRequest;
import app.user.AppUser;
import app.service.AppUserService;
import app.service.GoogleAuthService;
import app.service.SessionAuthenticationService;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final GoogleAuthService googleAuthService;
    private final AppUserService appUserService;
    private final SessionAuthenticationService sessionAuthenticationService;

    public AuthController(
            GoogleAuthService googleAuthService,
            AppUserService appUserService,
            SessionAuthenticationService sessionAuthenticationService
    ) {
        this.googleAuthService = googleAuthService;
        this.appUserService = appUserService;
        this.sessionAuthenticationService = sessionAuthenticationService;
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> loginWithGoogle(
            @Valid @RequestBody GoogleLoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        GoogleIdToken.Payload payload = googleAuthService.verifyToken(request.credential());
        AppUser user = appUserService.findOrCreateUser(payload);

        sessionAuthenticationService.authenticate(user, httpRequest, httpResponse);

        return ResponseEntity.ok(AuthResponse.from(user));
    }
}
