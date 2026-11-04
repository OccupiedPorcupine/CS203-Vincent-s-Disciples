package app.service;

import app.user.*;
import app.exception.AccountLinkingRequiredException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService {

    private final AppUserRepository appUserRepository;
    private final FindByIndexNameSessionRepository<?> sessions;

    public AppUserService(AppUserRepository appUserRepository, FindByIndexNameSessionRepository<?> sessions) {
        this.appUserRepository = appUserRepository;
        this.sessions = sessions;
    }

    // One transaction for the lookups and the save, so they see a consistent view of the user.
    @Transactional
    public AppUser findOrCreateUser(GoogleIdToken.Payload payload) {
        String googleId = payload.getSubject();
        String email = normalizeEmail(payload.getEmail());
        String name = (String) payload.get("name");
        String pictureUrl = (String) payload.get("picture");

        return appUserRepository.findByGoogleId(googleId)
                .map(existingUser -> updateGoogleProfile(existingUser, email, name, pictureUrl))
                .orElseGet(() -> createGoogleUser(googleId, email, name, pictureUrl));
    }

    private AppUser updateGoogleProfile(
            AppUser user,
            String email,
            String name,
            String pictureUrl
    ) {
        // Compare IDs, not object references: separate lookups can return different Java
        // objects for the same row, which made every returning Google user look like a
        // different account owning their own email.
        appUserRepository.findByEmailIgnoreCase(email)
                .filter(emailOwner -> !emailOwner.getId().equals(user.getId()))
                .ifPresent(emailOwner -> {
                    throw new AccountLinkingRequiredException();
                });
        user.updateProfile(email, name, pictureUrl);
        return appUserRepository.save(user);
    }

    private AppUser createGoogleUser(
            String googleId,
            String email,
            String name,
            String pictureUrl
    ) {
        AppUser emailOwner = appUserRepository.findByEmailIgnoreCase(email).orElse(null);
        if (emailOwner == null) {
            return appUserRepository.save(new AppUser(googleId, email, name, pictureUrl));
        }
        // Anyone can sign up with an address they don't own (#117). Google has verified that
        // this person owns it, so an account that never confirmed the address is theirs: its
        // password goes and whoever made it is signed out. A confirmed account still needs
        // linking, since matching email alone is not sufficient proof to link two login methods.
        if (emailOwner.isEmailVerified() || emailOwner.getGoogleId() != null) {
            throw new AccountLinkingRequiredException();
        }
        emailOwner.claimWithGoogle(googleId, email, name, pictureUrl);
        sessions.findByPrincipalName(String.valueOf(emailOwner.getId())).keySet().forEach(sessions::deleteById);
        return appUserRepository.save(emailOwner);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
