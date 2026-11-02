package app.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

// Daily allowances per plan, from app.assistant.plans.* in application.properties. A voice
// recording uses the same allowance as messages: each voice message needs one of each.
@Component
public class AssistantPlans {

    public record Allowance(int messagesPerDay, int spokenRepliesPerDay) {}

    private final Map<AssistantPlan, Allowance> allowances;

    public AssistantPlans(
            @Value("${app.assistant.plans.free.messages-per-day}") int freeMessages,
            @Value("${app.assistant.plans.free.spoken-replies-per-day}") int freeSpoken,
            @Value("${app.assistant.plans.standard.messages-per-day}") int standardMessages,
            @Value("${app.assistant.plans.standard.spoken-replies-per-day}") int standardSpoken,
            @Value("${app.assistant.plans.pro.messages-per-day}") int proMessages,
            @Value("${app.assistant.plans.pro.spoken-replies-per-day}") int proSpoken
    ) {
        this.allowances = Map.of(
                AssistantPlan.FREE, new Allowance(freeMessages, freeSpoken),
                AssistantPlan.STANDARD, new Allowance(standardMessages, standardSpoken),
                AssistantPlan.PRO, new Allowance(proMessages, proSpoken));
    }

    public Allowance of(AssistantPlan plan) {
        return allowances.get(plan);
    }
}
