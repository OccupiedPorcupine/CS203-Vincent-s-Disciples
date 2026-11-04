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
                                .map(link -> new ChatLinksResponse.LinkedChat(link.getId(), link.getDisplayName(), link.getLinkedAt()))
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
        ChatLink link = links.findByIdAndOwner_Id(linkId, ownerId).orElseThrow(() ->
                new AssistantException(HttpStatus.NOT_FOUND, "CHAT_LINK_NOT_FOUND", "That chat isn't linked to your account."));
        links.delete(link);
        log.info("User {} unlinked their {} chat", ownerId, link.getChannel());
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

    private static String truncate(String name) {
        if (name == null || name.isBlank()) return null;
        String stripped = name.strip();
        return stripped.length() <= 100 ? stripped : stripped.substring(0, 100);
    }
}
