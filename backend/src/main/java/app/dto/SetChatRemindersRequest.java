package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalTime;

// Both are Singapore time, on the hour from 12:00 to 23:00; null turns that message off.
public record SetChatRemindersRequest(
        @Schema(type = "string", description = "When to remind the owner to send the day's sales; null for never", example = "21:00")
        LocalTime salesReminderAt,
        @Schema(type = "string", description = "When to send tomorrow's prep list; null for never", example = "20:00")
        LocalTime prepListAt
) {}
