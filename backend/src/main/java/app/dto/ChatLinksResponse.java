package app.dto;

import app.chat.ChatChannel;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalTime;
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
            Instant linkedAt,
            @Schema(type = "string", description = "When the daily sales reminder is sent, Singapore time; null if off", example = "21:00")
            @JsonFormat(pattern = "HH:mm") LocalTime salesReminderAt,
            @Schema(type = "string", description = "When tomorrow's prep list is sent, Singapore time; null if off", example = "20:00")
            @JsonFormat(pattern = "HH:mm") LocalTime prepListAt
    ) {}
}
