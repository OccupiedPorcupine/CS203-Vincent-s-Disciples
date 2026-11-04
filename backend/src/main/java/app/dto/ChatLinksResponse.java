package app.dto;

import app.chat.ChatChannel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record ChatLinksResponse(
        @Schema(description = "Chat apps set up on this server; empty if none") List<Channel> channels
) {
    public record Channel(
            @Schema(example = "TELEGRAM") ChatChannel channel,
            @Schema(example = "Telegram") String label,
            @Schema(description = "The account's linked chat on this channel; null if none") LinkedChat linked
    ) {}

    public record LinkedChat(
            @Schema(example = "4") Long id,
            @Schema(description = "The chat's name in the chat app, if it gave one", example = "Ah Hock") String displayName,
            Instant linkedAt
    ) {}
}
