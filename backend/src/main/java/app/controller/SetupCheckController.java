package app.controller;

import app.account.EmailSetupCheck;
import app.diagnostics.SetupChecks;
import app.diagnostics.SetupChecks.Outcome;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Admin")
@RestController
@RequestMapping("/api/admin/setup-checks")
public class SetupCheckController {

    private final SetupChecks setupChecks;
    private final EmailSetupCheck emailCheck;

    public SetupCheckController(SetupChecks setupChecks, EmailSetupCheck emailCheck) {
        this.setupChecks = setupChecks;
        this.emailCheck = emailCheck;
    }

    @Operation(summary = "Check the integrations", description = "Tries email, the ML service, the assistant, Google sign-in, "
            + "Telegram and chat reminders on this host. Each is OK, OFF (not set up; the app works without it) or PROBLEM, "
            + "with the reason. Takes up to 20 seconds. Never shows passwords, keys or tokens.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One entry per integration, by name"),
            @ApiResponse(responseCode = "403", description = "Not an admin", content = @Content)
    })
    @GetMapping
    public List<Outcome> check() {
        return setupChecks.run();
    }

    @Operation(summary = "Send a test email", description = "To the signed-in admin's address, straight away rather than in "
            + "the background like account emails, so an error from the email provider shows in the response.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK when the provider accepted it, otherwise PROBLEM or OFF with the reason"),
            @ApiResponse(responseCode = "403", description = "Not an admin", content = @Content)
    })
    @PostMapping("/email")
    public Outcome sendTestEmail(@AuthenticationPrincipal AuthenticatedUser user) {
        return Outcome.of("test email", emailCheck.sendTestEmail(user.email()));
    }
}
