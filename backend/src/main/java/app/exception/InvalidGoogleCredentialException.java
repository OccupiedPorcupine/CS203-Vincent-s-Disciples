package app.exception;

public class InvalidGoogleCredentialException extends RuntimeException {

    public InvalidGoogleCredentialException() {
        super("Google authentication failed");
    }

    public InvalidGoogleCredentialException(Throwable cause) {
        super("Google authentication failed", cause);
    }
}
