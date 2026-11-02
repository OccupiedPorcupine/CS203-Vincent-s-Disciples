package app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Google's `sub` claim is the stable identity key. Email is not.
    @Column(unique = true, updatable = false)
    private String googleId;

    @Column(nullable = false, unique = true)
    private String email;

    // Null for Google-only accounts. Never expose this value through an API.
    private String passwordHash;

    private String name;

    private String pictureUrl;

    @Column(nullable = false)
    private String role = "ROLE_USER";

    // When the user proved they own the email address; null until then. See V19__account_tokens.sql.
    private Instant emailVerifiedAt;

    protected AppUser() {
        // Required by JPA.
    }

    public AppUser(String googleId, String email, String name, String pictureUrl) {
        this.googleId = googleId;
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
        // Google only issues sign-in tokens for addresses it has verified.
        this.emailVerifiedAt = Instant.now();
    }

    public static AppUser localUser(String email, String passwordHash, String name) {
        AppUser user = new AppUser();
        user.email = email;
        user.passwordHash = passwordHash;
        user.name = name;
        return user;
    }

    public void updateProfile(String email, String name, String pictureUrl) {
        if (!email.equals(this.email) || emailVerifiedAt == null) {
            this.emailVerifiedAt = Instant.now();
        }
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void markEmailVerified(Instant at) {
        if (emailVerifiedAt == null) {
            emailVerifiedAt = at;
        }
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public Long getId() {
        return id;
    }

    public String getGoogleId() {
        return googleId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public String getName() {
        return name;
    }

    public String getPictureUrl() {
        return pictureUrl;
    }

    public String getRole() {
        return role;
    }
}
