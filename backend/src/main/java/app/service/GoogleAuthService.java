package app.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import app.exception.GoogleSignInNotConfiguredException;
import app.exception.InvalidGoogleCredentialException;

@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    // Null when GOOGLE_CLIENT_ID is blank: Google sign-in is off and email/password login still works.
    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthService(@Value("${google.client-id:}") String googleClientId)
            throws GeneralSecurityException, IOException {
        if (googleClientId.isBlank()) {
            log.warn("GOOGLE_CLIENT_ID is not set, so Google sign-in is off. Email/password login still works.");
            this.verifier = null;
            return;
        }
        if (!googleClientId.endsWith(".apps.googleusercontent.com")) {
            // A placeholder produces Google's "Error 401: invalid_client" popup (issue #16).
            log.warn("GOOGLE_CLIENT_ID does not end in .apps.googleusercontent.com. If it is a placeholder, "
                    + "Google sign-in will fail; leave it blank instead to turn Google sign-in off.");
        }
        this.verifier = new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance()
        )
                // Reject valid Google tokens that were issued for a different app.
                .setAudience(List.of(googleClientId))
                .build();
    }

    public GoogleIdToken.Payload verifyToken(String credential) {
        if (verifier == null) {
            throw new GoogleSignInNotConfiguredException();
        }
        if (credential == null || credential.isBlank()) {
            throw new InvalidGoogleCredentialException();
        }

        try {
            GoogleIdToken idToken = verifier.verify(credential);
            if (idToken == null || !Boolean.TRUE.equals(idToken.getPayload().getEmailVerified())) {
                throw new InvalidGoogleCredentialException();
            }
            return idToken.getPayload();
        } catch (GeneralSecurityException | IOException exception) {
            throw new InvalidGoogleCredentialException(exception);
        }
    }
}
