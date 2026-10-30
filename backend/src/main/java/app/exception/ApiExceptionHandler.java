package app.exception;

import app.assistant.AssistantException;
import app.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({InvalidGoogleCredentialException.class, InvalidLoginException.class})
    public ResponseEntity<ErrorResponse> authenticationFailed() {
        // Deliberately avoid revealing why a credential was rejected.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)  //checks for error 401
                .body(new ErrorResponse("AUTHENTICATION_FAILED", "Unable to authenticate"));
    }

    @ExceptionHandler(GoogleSignInNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> googleSignInNotConfigured(GoogleSignInNotConfiguredException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("GOOGLE_SIGN_IN_NOT_CONFIGURED", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validationFailed(MethodArgumentNotValidException exception) {
        log.warn("Request validation failed: {}", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage()).toList());
        return ResponseEntity.badRequest()  //error 400
                .body(new ErrorResponse("VALIDATION_FAILED", "The request is not valid"));
    }

    @ExceptionHandler(AccountAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> accountAlreadyExists() {
        return ResponseEntity.status(HttpStatus.CONFLICT)  //http error 409 iirc
                .body(new ErrorResponse("ACCOUNT_EXISTS", "An account already exists for this email"));
    }

    @ExceptionHandler(AccountLinkingRequiredException.class)
    public ResponseEntity<ErrorResponse> accountLinkingRequired() {
        return ResponseEntity.status(HttpStatus.CONFLICT) //http error 409 as well 
                .body(new ErrorResponse(
                        "ACCOUNT_LINKING_REQUIRED",
                        "Sign in to the existing account before linking Google"
                ));
    }

    @ExceptionHandler(AssistantException.class)
    public ResponseEntity<ErrorResponse> assistantError(AssistantException exception) {
        log.warn("Assistant request failed: {} {} ({})", exception.getStatus().value(), exception.getCode(), exception.getMessage());
        return ResponseEntity.status(exception.getStatus())
                .body(new ErrorResponse(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(AliasInUseException.class)
    public ResponseEntity<ErrorResponse> aliasInUse(AliasInUseException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("ALIAS_IN_USE", exception.getMessage()));
    }

    @ExceptionHandler(InvalidForecastDateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidForecastDate(InvalidForecastDateException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_FORECAST_DATE", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "The file exceeds 25 MB"));
    }
}
