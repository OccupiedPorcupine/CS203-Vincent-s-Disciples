package app.assistant;

import org.springframework.http.HttpStatus;

// Errors the assistant reports to the browser as {code, message} with the given status.
public class AssistantException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AssistantException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AssistantException unavailable(String message) {
        return new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE", message);
    }

    public static AssistantException upstream(String message) {
        return new AssistantException(HttpStatus.BAD_GATEWAY, "ASSISTANT_UPSTREAM_ERROR", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
