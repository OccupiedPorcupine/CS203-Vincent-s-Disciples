package app.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// The use* methods add one only while under the limit, in a single statement, and return 1 if
// they did (0 means the limit is reached). The day's row must exist first.
public interface AssistantDailyUsageRepository extends JpaRepository<AssistantDailyUsage, Long> {

    Optional<AssistantDailyUsage> findByOwnerIdAndUsageDate(Long ownerId, LocalDate usageDate);

    boolean existsByOwnerIdAndUsageDate(Long ownerId, LocalDate usageDate);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.messages = u.messages + 1 "
            + "where u.owner.id = :ownerId and u.usageDate = :date and u.messages < :limit")
    int useMessage(@Param("ownerId") Long ownerId, @Param("date") LocalDate date, @Param("limit") int limit);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.transcriptions = u.transcriptions + 1 "
            + "where u.owner.id = :ownerId and u.usageDate = :date and u.transcriptions < :limit")
    int useTranscription(@Param("ownerId") Long ownerId, @Param("date") LocalDate date, @Param("limit") int limit);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.spokenReplies = u.spokenReplies + 1 "
            + "where u.owner.id = :ownerId and u.usageDate = :date and u.spokenReplies < :limit")
    int useSpokenReply(@Param("ownerId") Long ownerId, @Param("date") LocalDate date, @Param("limit") int limit);

    // Gives back a message, recording or spoken reply that failed, so errors don't use the allowance.
    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.messages = u.messages - 1 "
            + "where u.owner.id = :ownerId and u.usageDate = :date and u.messages > 0")
    int refundMessage(@Param("ownerId") Long ownerId, @Param("date") LocalDate date);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.transcriptions = u.transcriptions - 1 "
            + "where u.owner.id = :ownerId and u.usageDate = :date and u.transcriptions > 0")
    int refundTranscription(@Param("ownerId") Long ownerId, @Param("date") LocalDate date);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.spokenReplies = u.spokenReplies - 1 "
            + "where u.owner.id = :ownerId and u.usageDate = :date and u.spokenReplies > 0")
    int refundSpokenReply(@Param("ownerId") Long ownerId, @Param("date") LocalDate date);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.inputTokens = u.inputTokens + :input, "
            + "u.cachedTokens = u.cachedTokens + :cached, u.outputTokens = u.outputTokens + :output "
            + "where u.owner.id = :ownerId and u.usageDate = :date")
    int addTokens(@Param("ownerId") Long ownerId, @Param("date") LocalDate date,
                  @Param("input") long input, @Param("cached") long cached, @Param("output") long output);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.transcribedSeconds = u.transcribedSeconds + :seconds "
            + "where u.owner.id = :ownerId and u.usageDate = :date")
    int addTranscribedSeconds(@Param("ownerId") Long ownerId, @Param("date") LocalDate date, @Param("seconds") int seconds);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.spokenCharacters = u.spokenCharacters + :characters "
            + "where u.owner.id = :ownerId and u.usageDate = :date")
    int addSpokenCharacters(@Param("ownerId") Long ownerId, @Param("date") LocalDate date, @Param("characters") int characters);

    @Transactional
    @Modifying
    @Query("update AssistantDailyUsage u set u.embeddingTokens = u.embeddingTokens + :tokens "
            + "where u.owner.id = :ownerId and u.usageDate = :date")
    int addEmbeddingTokens(@Param("ownerId") Long ownerId, @Param("date") LocalDate date, @Param("tokens") long tokens);

    @Query("select new app.assistant.AccountUsageTotals(o.id, o.email, o.name, o.plan, "
            + "sum(u.messages), sum(u.transcriptions), sum(u.spokenReplies), "
            + "sum(u.inputTokens), sum(u.cachedTokens), sum(u.outputTokens), "
            + "sum(u.transcribedSeconds), sum(u.spokenCharacters), sum(u.embeddingTokens)) "
            + "from AssistantDailyUsage u join u.owner o where u.usageDate >= :from "
            + "group by o.id, o.email, o.name, o.plan")
    List<AccountUsageTotals> totalsSince(@Param("from") LocalDate from);
}
