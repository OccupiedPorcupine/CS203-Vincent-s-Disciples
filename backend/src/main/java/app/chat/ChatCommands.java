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
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Chat commands answered straight from the account's data, without the language model, so they
// are instant and free (issue #100): /today, /prep, /forecast and /remind (#101). Any chat app can offer them.
@Service
public class ChatCommands {

    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    static final String NOTHING_TO_PREP = "Record a few days of sales first, then I can tell you what to prepare.";

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
        return forOwner(channel, externalId, ownerId -> prepFor(ownerId, LocalDate.now(clock).plusDays(1)));
    }

    // The prep list for a day, as text. Also sent each evening by ChatReminderService (#101).
    String prepFor(Long ownerId, LocalDate date) {
        PrepListResponse list = prepLists.prepList(ownerId, date, null);
        if (list.closed() != null) return list.closed();
        if (list.dishes().isEmpty()) return NOTHING_TO_PREP;

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

    // /remind: shows the chat's reminder settings, or changes them (#101). "sales 9pm", "prep 8pm",
    // "sales off", "prep on" (9 pm) and "off" (both). A time without am or pm is read as afternoon or
    // evening, since reminders only go out from noon.
    @Transactional
    public String remind(ChatChannel channel, String externalId, String argument) {
        Optional<ChatLink> found = links.findByChannelAndExternalId(channel, externalId);
        if (found.isEmpty()) return ChatMessageService.NOT_LINKED;
        ChatLink link = found.get();

        String[] words = argument.strip().toLowerCase(Locale.ROOT).split("\\s+", 2);
        if (words[0].isEmpty()) return reminders(link);
        if (words[0].equals("off") && words.length == 1) {
            link.setReminders(null, null);
            return "Reminders off. Turn them back on with /remind sales 9pm or /remind prep 9pm.";
        }
        boolean sales = words[0].equals("sales");
        if ((!sales && !words[0].equals("prep")) || words.length < 2) return REMIND_HELP;

        LocalTime time;
        if (words[1].equals("off")) {
            time = null;
        } else if (words[1].equals("on")) {
            time = ChatLinkService.DEFAULT_REMINDER;
        } else {
            time = reminderTime(words[1]);
            if (time == null || !ChatLinkService.isReminderTime(time)) return ChatLinkService.REMINDER_TIMES + " For example, /remind sales 9pm.";
        }
        if (sales) {
            link.setReminders(time, link.getPrepListAt());
        } else {
            link.setReminders(link.getSalesReminderAt(), time);
        }
        return reminders(link);
    }

    static final String REMIND_HELP = """
            /remind sales 9pm – remind me to send the day's sales
            /remind prep 8pm – send tomorrow's prep list
            /remind sales off, /remind prep off, or /remind off for both""";

    private static String reminders(ChatLink link) {
        return "Sales reminder: " + clockTime(link.getSalesReminderAt()) + ". Prep list for tomorrow: "
                + clockTime(link.getPrepListAt()) + ".\n\n" + REMIND_HELP;
    }

    // "9pm", "9 pm", "9:00pm", "21:00" and "9" are all 21:00. Null if it isn't a time.
    static LocalTime reminderTime(String text) {
        Matcher matcher = TIME.matcher(text.strip());
        if (!matcher.matches()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        String half = matcher.group(3);
        if (hour > 23 || minute > 59 || half != null && (hour == 0 || hour > 12)) return null;
        if (half == null ? hour < 12 : half.equals("pm") && hour < 12) hour += 12;
        if ("am".equals(half) && hour == 12) hour = 0;
        return LocalTime.of(hour, minute);
    }

    // 21:00 is "9 pm", 12:00 is "12 pm".
    static String clockTime(LocalTime time) {
        if (time == null) return "off";
        int hour = time.getHour() % 12 == 0 ? 12 : time.getHour() % 12;
        return hour + (time.getMinute() == 0 ? "" : String.format(":%02d", time.getMinute())) + (time.getHour() < 12 ? " am" : " pm");
    }

    private String forOwner(ChatChannel channel, String externalId, Function<Long, String> answer) {
        Optional<ChatLink> link = links.findByChannelAndExternalId(channel, externalId);
        return link.map(found -> answer.apply(found.getOwnerId())).orElse(ChatMessageService.NOT_LINKED);
    }

    private static String amount(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
