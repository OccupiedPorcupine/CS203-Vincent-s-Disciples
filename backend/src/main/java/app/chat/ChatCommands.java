package app.chat;

import app.dto.PrepListResponse;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import app.service.PrepListService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

// Chat commands answered straight from the account's data, without the language model, so they
// are instant and free (issue #100): /today, /prep and /forecast. Any chat app can offer them.
@Service
public class ChatCommands {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final ChatLinkRepository links;
    private final DailySaleRepository sales;
    private final PrepListService prepLists;
    private final Clock clock;

    public ChatCommands(ChatLinkRepository links, DailySaleRepository sales, PrepListService prepLists, Clock clock) {
        this.links = links;
        this.sales = sales;
        this.prepLists = prepLists;
        this.clock = clock;
    }

    // Sales recorded for today.
    @Transactional(readOnly = true)
    public String today(ChatChannel channel, String externalId) {
        return forOwner(channel, externalId, ownerId -> {
            LocalDate today = LocalDate.now(clock);
            List<DailySale> recorded = sales.findByDishOwnerIdAndSaleDateBetween(ownerId, today, today).stream()
                    .sorted(Comparator.comparing(sale -> sale.getDish().getName(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (recorded.isEmpty()) {
                return "No sales recorded for today (" + DAY.format(today) + ") yet. Send them like \"chicken rice 42, char siew 30\".";
            }
            StringBuilder text = new StringBuilder("Sales today, ").append(DAY.format(today)).append(':');
            recorded.forEach(sale -> text.append("\n• ").append(sale.getDish().getName()).append(' ').append(sale.getQuantitySold()));
            text.append("\nTotal: ").append(recorded.stream().mapToInt(DailySale::getQuantitySold).sum()).append(" portions");
            return text.toString();
        });
    }

    // Tomorrow's prep list (#45): portions per dish, then the ingredients they need.
    public String prep(ChatChannel channel, String externalId) {
        return forOwner(channel, externalId, ownerId -> {
            PrepListResponse list = prepLists.prepList(ownerId, LocalDate.now(clock).plusDays(1), null);
            if (list.closed() != null) return list.closed();
            if (list.dishes().isEmpty()) return "Record a few days of sales first, then I can tell you what to prepare.";

            StringBuilder text = new StringBuilder("Prep for ").append(DAY.format(list.date())).append(':');
            list.dishes().forEach(dish -> text.append("\n• ").append(dish.dish()).append(' ').append(dish.portions()));
            if (list.ingredients().isEmpty()) {
                text.append("\n\nAdd recipes on the Prep list page to see the ingredients too.");
            } else {
                text.append("\n\nIngredients:");
                list.ingredients().forEach(ingredient -> text.append("\n• ").append(ingredient.ingredient()).append(' ')
                        .append(amount(ingredient.quantity())).append(' ').append(ingredient.unit()));
            }
            return text.toString();
        });
    }

    // Tomorrow's forecast for each dish, with its likely range.
    public String forecast(ChatChannel channel, String externalId) {
        return forOwner(channel, externalId, ownerId -> {
            PrepListResponse list = prepLists.prepList(ownerId, LocalDate.now(clock).plusDays(1), null);
            if (list.closed() != null) return list.closed();
            if (list.dishes().isEmpty()) return "Record a few days of sales first, then I can forecast them.";

            StringBuilder text = new StringBuilder("Forecast for ").append(DAY.format(list.date())).append(':');
            list.dishes().forEach(dish -> text.append("\n• ").append(dish.dish()).append(" about ")
                    .append(Math.round(dish.expected())).append(" (likely ").append(dish.low()).append('–').append(dish.high()).append(')'));
            return text.toString();
        });
    }

    private String forOwner(ChatChannel channel, String externalId, Function<Long, String> answer) {
        Optional<ChatLink> link = links.findByChannelAndExternalId(channel, externalId);
        return link.map(found -> answer.apply(found.getOwnerId())).orElse(ChatMessageService.NOT_LINKED);
    }

    private static String amount(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
