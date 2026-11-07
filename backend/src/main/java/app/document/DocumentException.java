package app.document;

import org.springframework.http.HttpStatus;

// Errors from the Documents API, reported as {code, message} with the given status.
public class DocumentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DocumentException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static DocumentException invalid(String code, String message) {
        return new DocumentException(HttpStatus.BAD_REQUEST, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
