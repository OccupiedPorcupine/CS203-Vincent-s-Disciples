package app.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ChatInboundMessageRepository extends JpaRepository<ChatInboundMessage, Long> {
    boolean existsByChannelAndMessageId(ChatChannel channel, String messageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatInboundMessage message WHERE message.receivedAt < :cutoff")
    int deleteReceivedBefore(Instant cutoff);
}
