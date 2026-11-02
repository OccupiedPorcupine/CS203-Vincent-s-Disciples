package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AssistantUsageResponse(
        @Schema(description = "The account's plan", example = "FREE") String plan,
        @Schema(description = "Messages sent today (Singapore time)", example = "34") int messagesUsed,
        @Schema(description = "Messages allowed a day; voice recordings have the same allowance", example = "50") int messagesPerDay,
        @Schema(description = "Spoken replies today; replies read from the cache aren't counted", example = "12") int spokenRepliesUsed,
        @Schema(description = "Spoken replies allowed a day", example = "20") int spokenRepliesPerDay
) {}
