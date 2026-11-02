package app.account;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.Instant;

// A single-use link sent by email: a password reset or an email verification. Only the token's
// SHA-256 hash is stored. See V19__account_tokens.sql.
@Entity
@Table(name = "account_tokens")
public class AccountToken {

    public enum Purpose { PASSWORD_RESET, EMAIL_VERIFICATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Purpose purpose;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    protected AccountToken() {
    }

    public AccountToken(AppUser user, Purpose purpose, String tokenHash, Instant createdAt, Instant expiresAt) {
        this.user = user;
        this.purpose = purpose;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public AppUser getUser() {
        return user;
    }

    public Purpose getPurpose() {
        return purpose;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void use(Instant now) {
        this.usedAt = now;
    }
}
