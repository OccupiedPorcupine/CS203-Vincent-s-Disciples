package app.assistant;

// One account's assistant use summed over a period, for the admin view.
public record AccountUsageTotals(
        Long userId, String email, String name, String plan,
        Long messages, Long transcriptions, Long spokenReplies,
        Long inputTokens, Long cachedTokens, Long outputTokens,
        Long transcribedSeconds, Long spokenCharacters
) {}
