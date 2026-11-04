package app.chat;

import app.account.AccountToken.Purpose;
import app.account.AccountTokens;
import app.assistant.AssistantException;
import app.dto.ChatLinkCodeResponse;
import app.dto.ChatLinksResponse;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

// Linking a chat app's chat to an account (issue #99). The owner asks for a one-time code on the
// Account page and opens it in the chat app; the chat sends the code back through its connector,
// which proves the chat belongs to the owner. Codes are account tokens: stored hashed, single use,
// valid for CODE_LIFETIME.
@Service
public class ChatLinkService {

    private static final Logger log = LoggerFactory.getLogger(ChatLinkService.class);

    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);

    // Reminder and prep list times (#101) are Singapore time, on the hour, from noon to 11 pm:
    // the job that sends them runs hourly at five past (.github/workflows/chat-reminders.yml).
    static final LocalTime EARLIEST_REMINDER = LocalTime.NOON;
    static final LocalTime LATEST_REMINDER = LocalTime.of(23, 0);
    static final LocalTime DEFAULT_REMINDER = LocalTime.of(21, 0);
    static final String REMINDER_TIMES = "Reminders can be sent on the hour, from 12 pm to 11 pm.";

    private final ChatLinkRepository links;
    private final AppUserRepository users;
    private final AccountTokens tokens;
    private final Map<ChatChannel, ChatConnector> connectors = new EnumMap<>(ChatChannel.class);
    private final Clock clock;

    public ChatLinkService(ChatLinkRepository links, AppUserRepository users, AccountTokens tokens,
                           ObjectProvider<ChatConnector> connectors, Clock clock) {
        this.links = links;
        this.users = users;
        this.tokens = tokens;
        connectors.orderedStream().forEach(connector -> this.connectors.put(connector.channel(), connector));
        this.clock = clock;
    }

    // Each channel configured on this server, with the owner's linked chat on it if any.
    @Transactional(readOnly = true)
    public ChatLinksResponse list(Long ownerId) {
        return new ChatLinksResponse(connectors.keySet().stream()
                .map(channel -> new ChatLinksResponse.Channel(channel, channel.label(),
                        links.findByOwner_IdAndChannel(ownerId, channel)
                                .map(link -> new ChatLinksResponse.LinkedChat(link.getId(), link.getDisplayName(), link.getLinkedAt(),
                                        link.getSalesReminderAt(), link.getPrepListAt()))
                                .orElse(null)))
                .toList());
    }

    @Transactional
    public ChatLinkCodeResponse issueCode(Long ownerId, ChatChannel channel) {
        ChatConnector connector = connector(channel);
        String code = tokens.issue(users.findById(ownerId).orElseThrow(), Purpose.CHAT_LINK, CODE_LIFETIME);
        return new ChatLinkCodeResponse(code, connector.linkFor(code), clock.instant().plus(CODE_LIFETIME));
    }

    // Links the chat to the code's account, replacing the account's earlier chat on this channel
    // and any account this chat was linked to before. Empty if the code is unknown, used or expired.
    @Transactional
    public Optional<ChatLink> link(ChatChannel channel, String externalId, String displayName, String code) {
        connector(channel);
        Optional<AppUser> owner = tokens.consume(code, Purpose.CHAT_LINK);
        if (owner.isEmpty()) {
            log.info("Chat link on {} refused: the code is unknown, used or expired", channel);
            return Optional.empty();
        }
        links.findByChannelAndExternalId(channel, externalId).ifPresent(links::delete);
        links.findByOwner_IdAndChannel(owner.get().getId(), channel).ifPresent(links::delete);
        links.flush();

        Instant now = clock.instant();
        ChatLink link = links.save(new ChatLink(owner.get(), channel, externalId, truncate(displayName), now));
        log.info("User {} linked a {} chat", owner.get().getId(), channel);
        return Optional.of(link);
    }

    @Transactional
    public void unlink(Long ownerId, Long linkId) {
        ChatLink link = links.findByIdAndOwner_Id(linkId, ownerId).orElseThrow(ChatLinkService::notLinked);
        links.delete(link);
        log.info("User {} unlinked their {} chat", ownerId, link.getChannel());
    }

    // Turns the sales reminder and the evening prep list on (at a time) or off (null) for a linked chat.
    @Transactional
    public ChatLinksResponse setReminders(Long ownerId, Long linkId, LocalTime salesReminderAt, LocalTime prepListAt) {
        ChatLink link = links.findByIdAndOwner_Id(linkId, ownerId).orElseThrow(ChatLinkService::notLinked);
        if (!isReminderTime(salesReminderAt) || !isReminderTime(prepListAt)) {
            throw new AssistantException(HttpStatus.BAD_REQUEST, "INVALID_REMINDER_TIME", REMINDER_TIMES);
        }
        link.setReminders(salesReminderAt, prepListAt);
        log.info("User {} set their {} reminders: sales {}, prep list {}", ownerId, link.getChannel(), salesReminderAt, prepListAt);
        return list(ownerId);
    }

    // Null is off, which is always allowed.
    static boolean isReminderTime(LocalTime time) {
        return time == null || (time.getMinute() == 0 && time.getSecond() == 0 && time.getNano() == 0
                && !time.isBefore(EARLIEST_REMINDER) && !time.isAfter(LATEST_REMINDER));
    }

    // From the chat itself, e.g. Telegram's /unlink. False if the chat wasn't linked.
    @Transactional
    public boolean unlinkChat(ChatChannel channel, String externalId) {
        Optional<ChatLink> link = links.findByChannelAndExternalId(channel, externalId);
        link.ifPresent(found -> {
            links.delete(found);
            log.info("User {} unlinked their {} chat from the chat", found.getOwnerId(), channel);
        });
        return link.isPresent();
    }

    public boolean isConfigured(ChatChannel channel) {
        return connectors.containsKey(channel);
    }

    private ChatConnector connector(ChatChannel channel) {
        ChatConnector connector = connectors.get(channel);
        if (connector == null) {
            throw new AssistantException(HttpStatus.NOT_FOUND, "CHANNEL_NOT_CONFIGURED",
                    channel.label() + " isn't set up on this server.");
        }
        return connector;
    }

    private static AssistantException notLinked() {
        return new AssistantException(HttpStatus.NOT_FOUND, "CHAT_LINK_NOT_FOUND", "That chat isn't linked to your account.");
    }

    private static String truncate(String name) {
        if (name == null || name.isBlank()) return null;
        String stripped = name.strip();
        return stripped.length() <= 100 ? stripped : stripped.substring(0, 100);
    }
}
