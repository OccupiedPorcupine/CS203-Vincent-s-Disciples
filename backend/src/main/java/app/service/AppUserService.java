package app.service;

import app.user.*;
import app.exception.AccountLinkingRequiredException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import org.springframework.stereotype.Service;

@Service
public class AppUserService {

    private final AppUserRepository appUserRepository;

    public AppUserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

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
        appUserRepository.findByEmailIgnoreCase(email)
                .filter(emailOwner -> emailOwner != user)
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
        // Matching email alone is not sufficient proof to link two login methods.
        if (appUserRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new AccountLinkingRequiredException();
        }
        return appUserRepository.save(new AppUser(googleId, email, name, pictureUrl));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
