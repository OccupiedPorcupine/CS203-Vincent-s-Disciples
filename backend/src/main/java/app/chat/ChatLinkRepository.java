package app.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatLinkRepository extends JpaRepository<ChatLink, Long> {
    Optional<ChatLink> findByChannelAndExternalId(ChatChannel channel, String externalId);
    Optional<ChatLink> findByOwner_IdAndChannel(Long ownerId, ChatChannel channel);
    Optional<ChatLink> findByIdAndOwner_Id(Long id, Long ownerId);
    List<ChatLink> findByOwner_IdOrderByChannelAsc(Long ownerId);
}
