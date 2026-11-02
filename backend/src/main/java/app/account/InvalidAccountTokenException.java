package app.account;

public class InvalidAccountTokenException extends RuntimeException {
    public InvalidAccountTokenException(String message) {
        super(message);
    }
}
