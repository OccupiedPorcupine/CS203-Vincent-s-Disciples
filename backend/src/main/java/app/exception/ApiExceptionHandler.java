package app.exception;

import app.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({InvalidGoogleCredentialException.class, InvalidLoginException.class})
    public ResponseEntity<ErrorResponse> authenticationFailed() {
        // Deliberately avoid revealing why a credential was rejected.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)  //checks for error 401
                .body(new ErrorResponse("AUTHENTICATION_FAILED", "Unable to authenticate"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validationFailed() {
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
}
