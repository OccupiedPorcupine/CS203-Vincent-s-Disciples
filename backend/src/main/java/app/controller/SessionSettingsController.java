package app.controller;

import app.user.AppUserRepository;
import app.user.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account/session-settings")
public class SessionSettingsController {
    private final AppUserRepository users;

    public SessionSettingsController(AppUserRepository users) { this.users = users; }

    public record Settings(@Min(5) @Max(1440) int idleMinutes,
                           @Min(60) @Max(10080) int absoluteMinutes) {
        @AssertTrue(message = "Idle timeout must not exceed the maximum sign-in duration")
        public boolean isOrdered() { return idleMinutes <= absoluteMinutes; }
    }

    @GetMapping
    public Settings get(@AuthenticationPrincipal AuthenticatedUser principal) {
        var user = users.findById(principal.id()).orElseThrow();
        return new Settings(user.getSessionIdleMinutes(), user.getSessionAbsoluteMinutes());
    }

    @PutMapping
    @Transactional
    public Settings update(@AuthenticationPrincipal AuthenticatedUser principal,
                           @Valid @RequestBody Settings settings) {
        var user = users.findById(principal.id()).orElseThrow();
        user.changeSessionTimeouts(settings.idleMinutes(), settings.absoluteMinutes());
        return settings;
    }
}
