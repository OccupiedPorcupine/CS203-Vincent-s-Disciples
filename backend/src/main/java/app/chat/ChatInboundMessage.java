package app.chat;

import jakarta.persistence.*;

import java.time.Instant;

// A message a chat app delivered, kept so a resent copy isn't handled twice.
// See V29__chat_links.sql.
@Entity
@Table(name = "chat_inbound_messages")
public class ChatInboundMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatChannel channel;

    @Column(nullable = false, length = 64)
    private String messageId;

    @Column(nullable = false)
    private Instant receivedAt;

    protected ChatInboundMessage() {
    }

    public ChatInboundMessage(ChatChannel channel, String messageId, Instant receivedAt) {
        this.channel = channel;
        this.messageId = messageId;
        this.receivedAt = receivedAt;
    }
}
