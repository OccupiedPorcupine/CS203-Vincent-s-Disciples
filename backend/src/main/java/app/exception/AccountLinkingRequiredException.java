package app.exception;

public class AccountLinkingRequiredException extends RuntimeException {

    public AccountLinkingRequiredException() {
        super("An account already exists and must be linked after authentication");
    }
}
