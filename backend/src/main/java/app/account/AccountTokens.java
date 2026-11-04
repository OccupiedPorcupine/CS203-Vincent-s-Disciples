package app.account;

import app.account.AccountToken.Purpose;
import app.user.AppUser;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

// Random single-use tokens: emailed links (AccountService) and chat link codes (ChatLinkService).
// Only a token's SHA-256 hash is stored, so a database leak doesn't hand out working tokens.
@Component
public class AccountTokens {

    private final AccountTokenRepository tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccountTokens(AccountTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    // A new token replaces any earlier unused one with the same purpose, so only the latest works.
    // 43 characters of A-Z, a-z, 0-9, - and _, which also fits a Telegram start parameter.
    @Transactional
    public String issue(AppUser user, Purpose purpose, Duration lifetime) {
        Instant now = clock.instant();
        tokens.findByUserIdAndPurposeAndUsedAtIsNull(user.getId(), purpose).forEach(old -> old.use(now));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new AccountToken(user, purpose, hash(token), now, now.plus(lifetime)));
        return token;
    }

    // The token's user, if the token is for this purpose, unexpired and unused. Marks it used.
    @Transactional
    public Optional<AppUser> consume(String token, Purpose purpose) {
        Instant now = clock.instant();
        return tokens.findByTokenHash(hash(token))
                .filter(candidate -> candidate.getPurpose() == purpose && candidate.isUsable(now))
                .map(found -> {
                    found.use(now);
                    return found.getUser();
                });
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
