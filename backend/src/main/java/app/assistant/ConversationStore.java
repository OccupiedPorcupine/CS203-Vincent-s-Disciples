package app.assistant;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Recent conversation items per user and conversation, kept in memory so follow-ups such as
// "no, it was 45" have context. Lost on restart, which only means starting a new conversation.
@Component
public class ConversationStore {

    static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_ITEMS = 40;

    private record Key(Long ownerId, String conversationId) {}

    private record Conversation(List<JsonNode> items, Instant lastUsed) {}

    private final Map<Key, Conversation> conversations = new ConcurrentHashMap<>();
    private final Clock clock;

    public ConversationStore(Clock clock) {
        this.clock = clock;
    }

    public List<JsonNode> load(Long ownerId, String conversationId) {
        removeIdle();
        Conversation conversation = conversations.get(new Key(ownerId, conversationId));
        return conversation == null ? new ArrayList<>() : new ArrayList<>(conversation.items());
    }

    public void save(Long ownerId, String conversationId, List<JsonNode> items) {
        conversations.put(new Key(ownerId, conversationId), new Conversation(trim(items), clock.instant()));
    }

    // Drops the oldest turns, always cutting just before a user message so a function call is
    // never separated from its output (the API rejects that).
    static List<JsonNode> trim(List<JsonNode> items) {
        int start = 0;
        while (items.size() - start > MAX_ITEMS) {
            int next = start + 1;
            while (next < items.size() && !"user".equals(items.get(next).path("role").asString())) {
                next++;
            }
            if (next >= items.size()) {
                break;
            }
            start = next;
        }
        return List.copyOf(items.subList(start, items.size()));
    }

    private void removeIdle() {
        Instant cutoff = clock.instant().minus(IDLE_TIMEOUT);
        conversations.values().removeIf(conversation -> conversation.lastUsed().isBefore(cutoff));
    }
}
