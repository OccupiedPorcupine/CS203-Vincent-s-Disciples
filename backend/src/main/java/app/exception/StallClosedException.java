package app.exception;

public class StallClosedException extends RuntimeException {

    public StallClosedException(String message) {
        super(message);
    }
}
