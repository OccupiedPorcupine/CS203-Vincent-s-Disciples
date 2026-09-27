package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record PublicConfigResponse(
        @Schema(description = "Google OAuth client ID used by the frontend", example = "123456789-example.apps.googleusercontent.com") String googleClientId
) {}