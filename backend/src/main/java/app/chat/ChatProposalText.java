package app.chat;

import app.assistant.ProposedClosure;
import app.assistant.ProposedEntry;
import app.assistant.ProposedPrice;
import app.dto.AssistantMessageResponse;
import app.dto.ConfirmProposalResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// A confirm card as plain text, for chat apps that can't show the web card (issue #99):
//   Save these?
//   • Chicken rice, Sat 31 Oct: 40 → 45
//   • Closed Sun 1 Nov (Centre cleaning)
// The chat shows Confirm and Cancel under it. A chat card can't be edited: the owner sends a
// correction instead, which makes a new proposal.
public final class ChatProposalText {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private ChatProposalText() {
    }

    public static String proposal(AssistantMessageResponse.Proposal proposal) {
        if (proposal.note() != null) {
            return "Save this note?\n" + proposal.note().title() + ": " + proposal.note().text();
        }
        if (proposal.priceChange() != null) {
            return "Change this price?\n" + price(proposal.priceChange());
        }
        return "Save these?\n" + String.join("\n", lines(proposal.entries(), proposal.closures()));
    }

    public static String saved(ConfirmProposalResponse response) {
        if (response.note() != null) {
            return "Saved as a note: " + response.note().title() + ". You can edit or delete it on the Documents page.";
        }
        if (response.priceChange() != null) {
            return "Saved.\n" + price(response.priceChange());
        }
        return "Saved.\n" + String.join("\n", lines(response.saved(), response.closures()));
    }

    private static List<String> lines(List<ProposedEntry> entries, List<ProposedClosure> closures) {
        List<String> lines = new ArrayList<>();
        if (entries != null) entries.forEach(entry -> lines.add("• " + sale(entry)));
        if (closures != null) closures.forEach(closure -> lines.add("• " + closure(closure)));
        return lines;
    }

    private static String sale(ProposedEntry entry) {
        String name = entry.isNewDish() ? entry.newDishName() + " (new dish)" : entry.dishName();
        StringBuilder line = new StringBuilder(name).append(", ").append(day(entry.date())).append(": ");
        if (entry.previousQuantity() != null && entry.previousQuantity() != entry.quantity()) {
            line.append(entry.previousQuantity()).append(" → ");
        }
        line.append(entry.quantity());
        if (entry.soldOutAt() != null) line.append(", sold out at ").append(entry.soldOutAt());
        if (entry.leftoverQuantity() != null) line.append(", ").append(entry.leftoverQuantity()).append(" left over");
        return line.toString();
    }

    private static String closure(ProposedClosure closure) {
        return (closure.closed() ? "Closed " : "Open again ") + day(closure.date())
                + (closure.reason() == null || closure.reason().isBlank() ? "" : " (" + closure.reason() + ")");
    }

    private static String price(ProposedPrice price) {
        StringBuilder line = new StringBuilder("• ").append(price.dishName()).append(": ");
        if (price.currentPrice() != null) line.append(dollars(price.currentPrice())).append(" → ");
        line.append(dollars(price.price()));
        if (price.unitCost() != null) line.append(", costing ").append(dollars(price.unitCost()));
        if (price.effectiveFrom() != null) line.append(", from ").append(day(price.effectiveFrom()));
        return line.toString();
    }

    private static String day(LocalDate date) {
        return DAY.format(date);
    }

    private static String dollars(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
