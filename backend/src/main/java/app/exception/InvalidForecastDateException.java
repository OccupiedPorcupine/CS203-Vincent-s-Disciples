package app.exception;

public class InvalidForecastDateException extends RuntimeException {
    public InvalidForecastDateException(String message) {
        super(message);
    }
}