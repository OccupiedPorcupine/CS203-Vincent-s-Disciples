package app.assistant;

import app.assistant.AssistantModelClient.Usage;
import app.dto.AccountUsageResponse;
import app.dto.AssistantUsageResponse;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;

// Daily assistant allowances per account, and what the assistant costs (issue #65).
// Messages, voice recordings and spoken replies each count against the account's plan for the
// day (Singapore time); the counts live in the database, so they hold across restarts and
// backend instances. The counting isn't transactional on purpose: each repository call is its
// own transaction, so a lost race to create the day's row doesn't spoil the next statement.
@Service
public class AssistantUsageService {

    private static final Logger log = LoggerFactory.getLogger(AssistantUsageService.class);

    private final AssistantDailyUsageRepository usage;
    private final AppUserRepository users;
    private final AssistantPlans plans;
    private final Clock clock;
    private final Prices prices;

    // OpenAI list prices in USD, for estimates only (app.assistant.prices.*).
    record Prices(double inputPerMillion, double cachedInputPerMillion, double outputPerMillion,
                  double transcriptionPerMinute, double speechPerThousandCharacters) {}

    public AssistantUsageService(
            AssistantDailyUsageRepository usage, AppUserRepository users, AssistantPlans plans, Clock clock,
            @Value("${app.assistant.prices.input-per-million-tokens}") double inputPerMillion,
            @Value("${app.assistant.prices.cached-input-per-million-tokens}") double cachedInputPerMillion,
            @Value("${app.assistant.prices.output-per-million-tokens}") double outputPerMillion,
            @Value("${app.assistant.prices.transcription-per-minute}") double transcriptionPerMinute,
            @Value("${app.assistant.prices.speech-per-thousand-characters}") double speechPerThousandCharacters) {
        this.usage = usage;
        this.users = users;
        this.plans = plans;
        this.clock = clock;
        this.prices = new Prices(inputPerMillion, cachedInputPerMillion, outputPerMillion,
                transcriptionPerMinute, speechPerThousandCharacters);
    }

    public void useMessage(Long ownerId) {
        int limit = allowance(ownerId).messagesPerDay();
        LocalDate today = todaysRow(ownerId);
        if (usage.useMessage(ownerId, today, limit) == 0) {
            throw dailyLimit(limit);
        }
    }

    public void useTranscription(Long ownerId) {
        int limit = allowance(ownerId).messagesPerDay();
        LocalDate today = todaysRow(ownerId);
        if (usage.useTranscription(ownerId, today, limit) == 0) {
            throw dailyLimit(limit);
        }
    }

    public void useSpokenReply(Long ownerId) {
        int limit = allowance(ownerId).spokenRepliesPerDay();
        LocalDate today = todaysRow(ownerId);
        if (usage.useSpokenReply(ownerId, today, limit) == 0) {
            throw new AssistantException(HttpStatus.TOO_MANY_REQUESTS, "SPEECH_LIMIT",
                    "You've used today's " + limit + " spoken replies, so replies are text only until midnight. "
                            + "今天的 " + limit + " 次语音回复已用完，午夜前只显示文字回复。");
        }
    }

    public void refundMessage(Long ownerId) {
        refund(() -> usage.refundMessage(ownerId, LocalDate.now(clock)));
    }

    public void refundTranscription(Long ownerId) {
        refund(() -> usage.refundTranscription(ownerId, LocalDate.now(clock)));
    }

    public void refundSpokenReply(Long ownerId) {
        refund(() -> usage.refundSpokenReply(ownerId, LocalDate.now(clock)));
    }

    public void recordTokens(Long ownerId, Usage tokens) {
        if (tokens.inputTokens() + tokens.outputTokens() > 0) {
            usage.addTokens(ownerId, todaysRow(ownerId), tokens.inputTokens(), tokens.cachedTokens(), tokens.outputTokens());
        }
    }

    public void recordTranscribedSeconds(Long ownerId, double seconds) {
        if (seconds > 0) {
            usage.addTranscribedSeconds(ownerId, todaysRow(ownerId), (int) Math.ceil(seconds));
        }
    }

    public void recordSpokenCharacters(Long ownerId, int characters) {
        usage.addSpokenCharacters(ownerId, todaysRow(ownerId), characters);
    }

    // "You've used 34 of 50 today", for the assistant panel.
    public AssistantUsageResponse today(Long ownerId) {
        AssistantPlan plan = planOf(ownerId);
        AssistantPlans.Allowance allowance = plans.of(plan);
        var row = usage.findByOwnerIdAndUsageDate(ownerId, LocalDate.now(clock));
        return new AssistantUsageResponse(plan.name(),
                row.map(AssistantDailyUsage::getMessages).orElse(0), allowance.messagesPerDay(),
                row.map(AssistantDailyUsage::getSpokenReplies).orElse(0), allowance.spokenRepliesPerDay());
    }

    // The accounts that cost the most over the last `days` days, today included.
    public List<AccountUsageResponse> topAccounts(int days, int limit) {
        LocalDate from = LocalDate.now(clock).minusDays(days - 1L);
        return usage.totalsSince(from).stream()
                .map(totals -> new AccountUsageResponse(totals.userId(), totals.email(), totals.name(), totals.plan(),
                        totals.messages(), totals.transcriptions(), totals.spokenReplies(),
                        totals.inputTokens(), totals.cachedTokens(), totals.outputTokens(),
                        totals.transcribedSeconds(), totals.spokenCharacters(), estimatedCost(totals)))
                .sorted(Comparator.comparing(AccountUsageResponse::estimatedCostUsd).reversed())
                .limit(limit)
                .toList();
    }

    @Transactional
    public AssistantPlan changePlan(Long userId, String planName) {
        AssistantPlan plan;
        try {
            plan = AssistantPlan.valueOf(planName == null ? "" : planName.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("The plan must be FREE, STANDARD or PRO.");
        }
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("There is no account with ID " + userId + "."));
        user.changePlan(plan.name());
        log.info("Account {} moved to the {} plan", userId, plan);
        return plan;
    }

    // OpenAI's input_tokens include the cached ones, which are billed at the cached price.
    BigDecimal estimatedCost(AccountUsageTotals totals) {
        double input = totals.inputTokens() - totals.cachedTokens();
        double cost = input * prices.inputPerMillion() / 1_000_000
                + totals.cachedTokens() * prices.cachedInputPerMillion() / 1_000_000
                + totals.outputTokens() * prices.outputPerMillion() / 1_000_000
                + totals.transcribedSeconds() / 60.0 * prices.transcriptionPerMinute()
                + totals.spokenCharacters() / 1000.0 * prices.speechPerThousandCharacters();
        return BigDecimal.valueOf(cost).setScale(4, RoundingMode.HALF_UP);
    }

    private AssistantPlans.Allowance allowance(Long ownerId) {
        return plans.of(planOf(ownerId));
    }

    private AssistantPlan planOf(Long ownerId) {
        return AssistantPlan.of(users.findById(ownerId).map(AppUser::getPlan).orElse(null));
    }

    // Today's date, after making sure the account has a row for it.
    private LocalDate todaysRow(Long ownerId) {
        LocalDate today = LocalDate.now(clock);
        if (!usage.existsByOwnerIdAndUsageDate(ownerId, today)) {
            try {
                usage.saveAndFlush(new AssistantDailyUsage(users.getReferenceById(ownerId), today));
            } catch (DataIntegrityViolationException createdByAnotherRequest) {
                // Another request for the same account created it first; use that one.
            }
        }
        return today;
    }

    private static void refund(IntSupplier update) {
        try {
            update.getAsInt();
        } catch (RuntimeException exception) {
            // The failure being reported matters more than the count being exact.
            log.warn("Could not give back assistant usage: {}", exception.toString());
        }
    }

    private static AssistantException dailyLimit(int limit) {
        return new AssistantException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT",
                "You've used all " + limit + " assistant messages for today. You can still enter sales yourself, "
                        + "and the assistant is back at midnight. "
                        + "今天的 " + limit + " 条助手消息已用完。您仍可以自己输入销售数据，助手会在午夜后恢复。");
    }
}
