package app.assistant;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.LocalDate;

// One account's assistant use on one day (Singapore time). See V27__assistant_usage.sql.
// The counters are changed with single UPDATE statements in AssistantDailyUsageRepository, so
// two requests at once can't both take the last message of the day.
@Entity
@Table(name = "assistant_daily_usage")
public class AssistantDailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private LocalDate usageDate;

    @Column(nullable = false)
    private int messages;

    @Column(nullable = false)
    private int transcriptions;

    @Column(nullable = false)
    private int spokenReplies;

    @Column(nullable = false)
    private long inputTokens;

    @Column(nullable = false)
    private long cachedTokens;

    @Column(nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private int transcribedSeconds;

    @Column(nullable = false)
    private int spokenCharacters;

    // Tokens embedded for document search (issue #152): documents when saved, questions when asked.
    @Column(nullable = false)
    private long embeddingTokens;

    protected AssistantDailyUsage() {
    }

    public AssistantDailyUsage(AppUser owner, LocalDate usageDate) {
        this.owner = owner;
        this.usageDate = usageDate;
    }

    public int getMessages() {
        return messages;
    }

    public int getTranscriptions() {
        return transcriptions;
    }

    public int getSpokenReplies() {
        return spokenReplies;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getCachedTokens() {
        return cachedTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public int getTranscribedSeconds() {
        return transcribedSeconds;
    }

    public int getSpokenCharacters() {
        return spokenCharacters;
    }

    public long getEmbeddingTokens() {
        return embeddingTokens;
    }
}
