package app.controller;

import app.account.AccountService;
import app.account.AuthRateLimiter;
import app.account.AuthRateLimiter.Limit;
import app.dto.NewPasswordRequest;
import app.dto.PasswordResetRequest;
import app.dto.VerifyEmailRequest;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account", description = "Password reset and email verification by emailed link")
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private final AccountService accountService;
    private final AuthRateLimiter rateLimiter;

    public AccountController(AccountService accountService, AuthRateLimiter rateLimiter) {
        this.accountService = accountService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Ask for a password reset link", description = "Emails a link that works once, within 30 minutes, "
            + "if an account uses the address. The response is the same either way.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Request accepted"),
            @ApiResponse(responseCode = "429", description = "Too many requests for this email or from this address", content = @Content)
    })
    @PostMapping("/password-reset")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check(Limit.RESET_PER_IP, httpRequest.getRemoteAddr());
        rateLimiter.check(Limit.RESET_PER_EMAIL, request.email().strip());
        accountService.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Choose a new password", description = "Uses the token from a reset link. Signs the account out everywhere.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed"),
            @ApiResponse(responseCode = "400", description = "Password too short, or the link has expired or was already used", content = @Content)
    })
    @PostMapping("/password-reset/confirm")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody NewPasswordRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check(Limit.TOKEN_PER_IP, httpRequest.getRemoteAddr());
        accountService.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Confirm an email address", description = "Uses the token from the verification link sent at signup.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Email confirmed"),
            @ApiResponse(responseCode = "400", description = "The link has expired or was already used", content = @Content)
    })
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request, HttpServletRequest httpRequest) {
        rateLimiter.check(Limit.TOKEN_PER_IP, httpRequest.getRemoteAddr());
        accountService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send the verification email again", description = "For the signed-in user, if their email isn't confirmed yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Sent, unless the email is already confirmed"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content),
            @ApiResponse(responseCode = "429", description = "Sent too often", content = @Content)
    })
    @PostMapping("/verify-email/resend")
    public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal AuthenticatedUser user) {
        rateLimiter.check(Limit.VERIFICATION_PER_USER, String.valueOf(user.id()));
        accountService.resendVerification(user.id());
        return ResponseEntity.accepted().build();
    }
}
