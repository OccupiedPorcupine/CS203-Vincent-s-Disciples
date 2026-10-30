package app.service;

import app.exception.GoogleSignInNotConfiguredException;
import app.exception.InvalidGoogleCredentialException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GoogleAuthServiceTest {

    @Test
    void blankClientIdTurnsGoogleSignInOff() throws Exception {
        GoogleAuthService service = new GoogleAuthService(" ");

        assertThrows(GoogleSignInNotConfiguredException.class, () -> service.verifyToken("any-token"));
    }

    @Test
    void configuredClientIdStillRejectsBlankCredentials() throws Exception {
        GoogleAuthService service = new GoogleAuthService("test-client-id.apps.googleusercontent.com");

        assertThrows(InvalidGoogleCredentialException.class, () -> service.verifyToken(" "));
    }
}
