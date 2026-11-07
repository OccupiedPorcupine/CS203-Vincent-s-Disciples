package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

public record AccountUsageResponse(
        Long userId,
        String email,
        String name,
        @Schema(example = "FREE") String plan,
        Long messages,
        Long voiceRecordings,
        Long spokenReplies,
        @Schema(description = "Input tokens, cached ones included") Long inputTokens,
        Long cachedTokens,
        Long outputTokens,
        Long transcribedSeconds,
        Long spokenCharacters,
        @Schema(description = "Tokens embedded for document search: documents when saved, questions when asked") Long embeddingTokens,
        @Schema(description = "Estimated at the list prices in app.assistant.prices.*; speech is estimated from characters", example = "0.4123")
        BigDecimal estimatedCostUsd
) {}
