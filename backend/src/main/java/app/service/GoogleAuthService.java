package app.service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

import app.exception.InvalidGoogleCredentialException;

@Service
public class GoogleAuthService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthService(@Value("${google.client-id}") String googleClientId)
            throws GeneralSecurityException, IOException {
        this.verifier = new GoogleIdTokenVerifier.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance()
        )
                // Reject valid Google tokens that were issued for a different app.
                .setAudience(List.of(googleClientId))
                .build();
    }

    public GoogleIdToken.Payload verifyToken(String credential) {
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
