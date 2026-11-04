package app.account;

import app.account.AccountToken.Purpose;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

// Password reset and email verification by emailed link (issue #49). A link carries a random
// token that expires and works once; the database keeps only its hash.
@Service
public class AccountService {

    static final Duration RESET_LINK_LIFETIME = Duration.ofMinutes(30);
    static final Duration VERIFICATION_LINK_LIFETIME = Duration.ofDays(2);

    private static final String EXPIRED = "This link has expired or has already been used. Ask for a new one.";

    private final AppUserRepository appUserRepository;
    private final AccountTokens tokens;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final FindByIndexNameSessionRepository<?> sessions;
    private final Clock clock;
    private final String baseUrl;

    public AccountService(AppUserRepository appUserRepository, AccountTokens tokens, PasswordEncoder passwordEncoder,
                          EmailSender emailSender, FindByIndexNameSessionRepository<?> sessions, Clock clock,
                          @Value("${app.base-url}") String baseUrl) {
        this.appUserRepository = appUserRepository;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.sessions = sessions;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // Emails a reset link if an account uses this address. Says nothing either way, so the
    // response can't be used to find out who has an account.
    @Transactional
    public void requestPasswordReset(String email) {
        appUserRepository.findByEmailIgnoreCase(email.strip().toLowerCase(Locale.ROOT)).ifPresent(user -> {
            String token = tokens.issue(user, Purpose.PASSWORD_RESET, RESET_LINK_LIFETIME);
            emailSender.send(user.getEmail(), "Reset your Chicky password", """
                    Hi %s,

                    Someone asked to reset the password for your Chicky account. To choose a new password, open this link within 30 minutes:

                    %s/index.html#reset=%s

                    If it wasn't you, ignore this email. Your password stays the same.
                    """.formatted(greeting(user), baseUrl, token));
        });
    }

    // Sets a new password, signs the user out everywhere and counts the address as verified,
    // since only its owner could open the link.
    @Transactional
    public void resetPassword(String token, String newPassword) {
        AppUser user = consume(token, Purpose.PASSWORD_RESET);
        user.changePassword(passwordEncoder.encode(newPassword));
        user.markEmailVerified(clock.instant());
        sessions.findByPrincipalName(String.valueOf(user.getId())).keySet().forEach(sessions::deleteById);
    }

    @Transactional
    public void sendVerification(AppUser user) {
        if (user.isEmailVerified()) {
            return;
        }
        String token = tokens.issue(user, Purpose.EMAIL_VERIFICATION, VERIFICATION_LINK_LIFETIME);
        emailSender.send(user.getEmail(), "Confirm your email for Chicky", """
                Hi %s,

                Welcome to Chicky. Please confirm this is your email address by opening this link:

                %s/index.html#verify=%s

                You'll need it if you ever forget your password. The link works for 2 days.
                """.formatted(greeting(user), baseUrl, token));
    }

    @Transactional
    public void resendVerification(Long userId) {
        sendVerification(appUserRepository.findById(userId).orElseThrow());
    }

    @Transactional
    public void verifyEmail(String token) {
        consume(token, Purpose.EMAIL_VERIFICATION).markEmailVerified(clock.instant());
    }

    private AppUser consume(String token, Purpose purpose) {
        return tokens.consume(token, purpose).orElseThrow(() -> new InvalidAccountTokenException(EXPIRED));
    }

    private static String greeting(AppUser user) {
        return user.getName() == null || user.getName().isBlank() ? "there" : user.getName();
    }
}
