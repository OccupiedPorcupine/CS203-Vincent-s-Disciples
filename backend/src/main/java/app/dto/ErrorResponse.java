package app.dto;

//just to convert HTTP errors into Java objects
//eg.
// {
//   "code": "AUTHENTICATION_FAILED", OR "401"
//   "message": "Unable to authenticate"
// }

public record ErrorResponse(String code, String message) {
}
