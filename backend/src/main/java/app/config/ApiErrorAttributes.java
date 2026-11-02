package app.config;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

// Errors that don't reach ApiExceptionHandler (401 and 403 from Spring Security, unknown paths,
// unsupported methods, unexpected failures) use the same {code, message} body as everything else,
// instead of Spring Boot's {timestamp, status, error, path}.
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Object status = super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults()).get("status");
        HttpStatus httpStatus = status instanceof Integer code ? HttpStatus.resolve(code) : null;
        if (httpStatus == null) {
            httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code(httpStatus));
        body.put("message", message(httpStatus));
        return body;
    }

    private static String code(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case BAD_REQUEST -> "INVALID_REQUEST";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : status.name();
        };
    }

    private static String message(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "Please log in again.";
            case FORBIDDEN -> "This request isn't allowed. Reload the page and try again.";
            case NOT_FOUND -> "Not found.";
            case BAD_REQUEST -> "The request is not valid.";
            default -> status.is5xxServerError() ? "Something went wrong on our side. Try again in a moment." : status.getReasonPhrase();
        };
    }
}
