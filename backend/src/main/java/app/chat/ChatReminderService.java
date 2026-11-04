package app.chat;

import app.sale.DailySaleRepository;
import app.service.StallClosureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// Messages a linked chat gets without asking (issue #101): a reminder to send the day's sales,
// and tomorrow's prep list. The backend can scale to zero on Vercel, so nothing runs on a timer
// in here: a scheduled GitHub Action calls POST /api/channels/jobs/run (ChatJobController) every
// hour, and each run sends whatever has come due today and hasn't been sent yet. The texts come
// straight from the database, like /prep, so they cost nothing per message.
@Service
public class ChatReminderService {

    private static final Logger log = LoggerFactory.getLogger(ChatReminderService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    public record Run(int sent, int skipped, int failed) {}

    private final ChatLinkRepository links;
    private final DailySaleRepository sales;
    private final StallClosureService closures;
    private final ChatCommands commands;
    private final Map<ChatChannel, ChatConnector> connectors = new EnumMap<>(ChatChannel.class);
    private final Clock clock;

    public ChatReminderService(ChatLinkRepository links, DailySaleRepository sales, StallClosureService closures,
                               ChatCommands commands, ObjectProvider<ChatConnector> connectors, Clock clock) {
        this.links = links;
        this.sales = sales;
        this.closures = closures;
        this.commands = commands;
        connectors.orderedStream().forEach(connector -> this.connectors.put(connector.channel(), connector));
        this.clock = clock;
    }

    public Run run() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (ChatLink link : links.findBySalesReminderAtIsNotNullOrPrepListAtIsNotNull()) {
            ChatConnector connector = connectors.get(link.getChannel());
            if (connector == null) continue;

            // Claimed before sending, so a run that overlaps this one can't send it too. A send that
            // fails isn't retried: a second try an hour later would mostly be noise.
            if (isDue(link.getSalesReminderAt(), now) && links.claimSalesReminder(link.getId(), today) == 1) {
                String text = salesReminder(link.getOwnerId(), today);
                if (text == null) skipped++;
                else if (send(connector, link, text)) sent++;
                else failed++;
            }
            if (isDue(link.getPrepListAt(), now) && links.claimPrepList(link.getId(), today) == 1) {
                String text = prepList(link.getOwnerId(), today.plusDays(1));
                if (text == null) skipped++;
                else if (send(connector, link, text)) sent++;
                else failed++;
            }
        }
        if (sent + skipped + failed > 0) {
            log.info("Chat reminders: {} sent, {} not needed, {} failed", sent, skipped, failed);
        }
        return new Run(sent, skipped, failed);
    }

    private static boolean isDue(LocalTime at, LocalDateTime now) {
        return at != null && !now.toLocalTime().isBefore(at);
    }

    // Null when the sales are in already, or the stall is closed today.
    String salesReminder(Long ownerId, LocalDate today) {
        if (!sales.findByDishOwnerIdAndSaleDateBetween(ownerId, today, today).isEmpty()) return null;
        if (closures.closureMessage(ownerId, today).isPresent()) return null;
        return "You haven't sent today's sales (" + DAY.format(today) + ") yet. Reply with them, like \"chicken rice 40, char siew 30\".";
    }

    // Null when there's nothing to say: no sales to forecast from, or a weekly rest day tomorrow.
    // A one-off closure (a day the owner closed, NEA cleaning) is worth a line, so the owner
    // doesn't go to market for nothing.
    String prepList(Long ownerId, LocalDate tomorrow) {
        if (closures.isRestDay(ownerId, tomorrow)) return null;
        Optional<String> closed = closures.closureMessage(ownerId, tomorrow);
        if (closed.isPresent()) return "Nothing to prepare for tomorrow. " + closed.get();
        String text = commands.prepFor(ownerId, tomorrow);
        return ChatCommands.NOTHING_TO_PREP.equals(text) ? null : text;
    }

    private boolean send(ChatConnector connector, ChatLink link, String text) {
        try {
            connector.send(link.getExternalId(), text);
            return true;
        } catch (RuntimeException exception) {
            log.warn("Chat reminder for user {} on {} failed: {}", link.getOwnerId(), link.getChannel(), exception.toString());
            return false;
        }
    }
}
