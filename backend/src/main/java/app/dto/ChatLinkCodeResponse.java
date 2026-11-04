package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record ChatLinkCodeResponse(
        @Schema(description = "One-time code; the chat app sends it back to link the chat") String code,
        @Schema(description = "Open this on the phone to start the chat with the code", example = "https://t.me/ChickyBot?start=Xb3…") String link,
        @Schema(description = "After this the code no longer works") Instant expiresAt
) {}
