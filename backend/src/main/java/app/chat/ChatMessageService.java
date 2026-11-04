package app.chat;

import app.assistant.AssistantException;
import app.assistant.AssistantService;
import app.dto.AssistantMessageRequest;
import app.dto.AssistantMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

// The part of every chat app that isn't specific to one (issue #99): finds the account a chat is
// linked to and passes its messages to the same assistant as the web, with page "chat". Channel
// connectors (Telegram #100, WhatsApp #102) only turn their app's messages into calls here and
// ChatReply back into messages. A chat that isn't linked can't read or change anything.
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    // A chat continues one assistant conversation until it has been quiet this long.
    static final Duration CONVERSATION_IDLE = Duration.ofHours(6);
    // How long a delivered message ID is remembered; chat apps resend within minutes.
    static final Duration DELIVERY_MEMORY = Duration.ofDays(2);
    static final int MAX_TEXT = 1000;

    static final String NOT_LINKED = "This chat isn't linked to a Chicky account yet. In Chicky, open Account, "
            + "then Chat apps, and tap Connect.";

    private final ChatLinkRepository links;
    private final ChatInboundMessageRepository inbound;
    private final AssistantService assistant;
    private final Clock clock;

    public ChatMessageService(ChatLinkRepository links, ChatInboundMessageRepository inbound,
                              AssistantService assistant, Clock clock) {
        this.links = links;
        this.inbound = inbound;
        this.assistant = assistant;
        this.clock = clock;
    }

    // False if this message was delivered before, so a resent copy is ignored rather than handled
    // twice. Connectors call it first with the app's message or update ID.
    public boolean firstDelivery(ChatChannel channel, String messageId) {
        Instant now = clock.instant();
        inbound.deleteReceivedBefore(now.minus(DELIVERY_MEMORY));
        if (inbound.existsByChannelAndMessageId(channel, messageId)) {
            return false;
        }
        try {
            inbound.saveAndFlush(new ChatInboundMessage(channel, messageId, now));
            return true;
        } catch (DataIntegrityViolationException alreadySaved) {
            // The same message arrived twice at once.
            return false;
        }
    }

    public boolean isLinked(ChatChannel channel, String externalId) {
        return links.findByChannelAndExternalId(channel, externalId).isPresent();
    }

    public ChatReply handle(ChatChannel channel, String externalId, ChatInput input) {
        Optional<ChatLink> found = links.findByChannelAndExternalId(channel, externalId);
        if (found.isEmpty()) {
            return ChatReply.text(NOT_LINKED);
        }
        ChatLink link = found.get();
        Long ownerId = link.getOwnerId();

        String heard = null;
        try {
            String text;
            String mode;
            if (input instanceof ChatInput.Voice voice) {
                text = assistant.transcribe(ownerId, voice.audio(), voice.contentType()).text().strip();
                if (text.isEmpty()) {
                    return ChatReply.text("I couldn't hear anything in that voice note. Please try again.");
                }
                heard = text;
                mode = "voice";
            } else {
                text = ((ChatInput.Text) input).text().strip();
                mode = "text";
            }
            if (text.isEmpty()) {
                return ChatReply.text("Tell me what you sold, like \"chicken rice 42, char siew 30\".");
            }
            if (text.length() > MAX_TEXT) {
                return new ChatReply(heard, "That message is too long for me. Please send it in shorter parts.", null);
            }

            String conversationId = link.conversationFor(clock.instant(), CONVERSATION_IDLE);
            links.save(link);

            AssistantMessageResponse response = assistant.handleMessage(ownerId, new AssistantMessageRequest(
                    conversationId, text, AssistantService.CHAT_PAGE,
                    channel.name().toLowerCase(Locale.ROOT) + "-" + mode, null, null));
            if (response.proposal() == null) {
                return new ChatReply(heard, response.reply(), null);
            }
            return new ChatReply(heard, response.reply() + "\n\n" + ChatProposalText.proposal(response.proposal()),
                    response.proposal().id());
        } catch (AssistantException exception) {
            log.info("{} message for user {} not handled: {}", channel, ownerId, exception.getCode());
            return new ChatReply(heard, exception.getMessage(), null);
        }
    }

    // Confirm on a proposal in the chat. Only the linked account's own proposals can be confirmed,
    // and confirming twice saves once.
    public ChatReply confirm(ChatChannel channel, String externalId, Long proposalId) {
        Optional<ChatLink> link = links.findByChannelAndExternalId(channel, externalId);
        if (link.isEmpty()) {
            return ChatReply.text(NOT_LINKED);
        }
        try {
            return ChatReply.text(ChatProposalText.saved(assistant.confirmProposal(link.get().getOwnerId(), proposalId, null)));
        } catch (AssistantException exception) {
            return ChatReply.text(exception.getMessage());
        }
    }

    public ChatReply cancel(ChatChannel channel, String externalId, Long proposalId) {
        Optional<ChatLink> link = links.findByChannelAndExternalId(channel, externalId);
        if (link.isEmpty()) {
            return ChatReply.text(NOT_LINKED);
        }
        try {
            assistant.cancelProposal(link.get().getOwnerId(), proposalId);
            return ChatReply.text("Cancelled. Nothing was saved.");
        } catch (AssistantException exception) {
            return ChatReply.text(exception.getMessage());
        }
    }
}
