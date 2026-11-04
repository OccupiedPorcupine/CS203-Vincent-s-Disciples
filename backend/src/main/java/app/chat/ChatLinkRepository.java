package app.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ChatLinkRepository extends JpaRepository<ChatLink, Long> {
    Optional<ChatLink> findByChannelAndExternalId(ChatChannel channel, String externalId);
    Optional<ChatLink> findByOwner_IdAndChannel(Long ownerId, ChatChannel channel);
    Optional<ChatLink> findByIdAndOwner_Id(Long id, Long ownerId);
    List<ChatLink> findByOwner_IdOrderByChannelAsc(Long ownerId);

    // Chats with a reminder or prep list turned on (#101).
    List<ChatLink> findBySalesReminderAtIsNotNullOrPrepListAtIsNotNull();

    // Marks the day's sales reminder as handled. 1 if this call did it, 0 if a run already had,
    // so only one of two overlapping runs sends.
    @Transactional
    @Modifying
    @Query("UPDATE ChatLink l SET l.salesReminderSentOn = :day WHERE l.id = :id "
            + "AND (l.salesReminderSentOn IS NULL OR l.salesReminderSentOn < :day)")
    int claimSalesReminder(Long id, LocalDate day);

    @Transactional
    @Modifying
    @Query("UPDATE ChatLink l SET l.prepListSentOn = :day WHERE l.id = :id "
            + "AND (l.prepListSentOn IS NULL OR l.prepListSentOn < :day)")
    int claimPrepList(Long id, LocalDate day);
}
