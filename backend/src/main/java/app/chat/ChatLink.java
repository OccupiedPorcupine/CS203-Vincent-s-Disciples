package app.chat;

import app.user.AppUser;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

// A chat in a chat app, linked to the account whose sales it records. See V29__chat_links.sql.
@Entity
@Table(name = "chat_links")
public class ChatLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatChannel channel;

    @Column(nullable = false, length = 64)
    private String externalId;

    @Column(length = 100)
    private String displayName;

    @Column(nullable = false)
    private Instant linkedAt;

    @Column(length = 64)
    private String conversationId;

    private Instant lastMessageAt;

    // When to send the sales reminder and tomorrow's prep list (#101); null while off. See V34.
    private LocalTime salesReminderAt;

    private LocalTime prepListAt;

    // Set only through ChatLinkRepository's claims, so two runs of the job can't both send.
    private LocalDate salesReminderSentOn;

    private LocalDate prepListSentOn;

    protected ChatLink() {
    }

    public ChatLink(AppUser owner, ChatChannel channel, String externalId, String displayName, Instant linkedAt) {
        this.owner = owner;
        this.channel = channel;
        this.externalId = externalId;
        this.displayName = displayName;
        this.linkedAt = linkedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return owner.getId();
    }

    public ChatChannel getChannel() {
        return channel;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public LocalTime getSalesReminderAt() {
        return salesReminderAt;
    }

    public LocalTime getPrepListAt() {
        return prepListAt;
    }

    public void setReminders(LocalTime salesReminderAt, LocalTime prepListAt) {
        this.salesReminderAt = salesReminderAt;
        this.prepListAt = prepListAt;
    }

    // The conversation a new message continues: the same one until the chat has been quiet for
    // idleTimeout, then a new one, so yesterday's talk doesn't steer today's sales.
    public String conversationFor(Instant now, Duration idleTimeout) {
        if (conversationId == null || lastMessageAt == null || lastMessageAt.plus(idleTimeout).isBefore(now)) {
            conversationId = UUID.randomUUID().toString();
        }
        lastMessageAt = now;
        return conversationId;
    }
}
