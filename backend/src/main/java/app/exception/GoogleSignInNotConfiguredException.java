package app.exception;

public class GoogleSignInNotConfiguredException extends RuntimeException {

    public GoogleSignInNotConfiguredException() {
        super("Google sign-in is not configured on this server");
    }
}
