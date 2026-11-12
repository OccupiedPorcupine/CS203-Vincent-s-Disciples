package app.assistant;

import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.ProposalEntryInput;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import app.service.DailySaleService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

// Checks sales entries from the model (before they are shown) and from the user (before they
// are saved). The model is never trusted: dishes must belong to the user, dates can't be in the
// future, and quantities must be plausible.
//
// The model only mentions what the user said, so for its entries (validateProposal) a missing
// quantity, sold-out time or leftover count is taken from the day's record: "sold out of char siew
// at one" keeps the quantity already recorded, and a corrected quantity keeps the sold-out time.
// Entries the user confirms (validate) are saved exactly as given.
//
// Model proposals get two more checks, so a message like "save 999 for every day this month"
// can't become a card: a quantity far above what the dish has sold lately, and a proposal that
// replaces many days already recorded, are sent back to the model to ask the user. The same
// quantity on many days isn't refused; see repeatedQuantities.
@Component
public class ProposalValidator {

    public static final int MAX_QUANTITY = 9999;
    public static final int MAX_DAYS_BACK = 365;
    public static final int MAX_ENTRIES = 50;
    private static final int MAX_NAME_LENGTH = 100;
    // A quantity is implausible above HISTORY_FACTOR times the dish's best day in the last
    // HISTORY_DAYS (and at least HISTORY_MARGIN above it). With fewer than HISTORY_MIN_DAYS of
    // history, above NO_HISTORY_MAX.
    static final int HISTORY_DAYS = 28;
    static final int HISTORY_MIN_DAYS = 3;
    static final int HISTORY_FACTOR = 3;
    static final int HISTORY_MARGIN = 50;
    static final int NO_HISTORY_MAX = 500;
    // More recorded days than this replaced in one proposal is sent back to the model.
    static final int MAX_REPLACED_DAYS = 3;
    // The same dish with the same quantity on more dates than this is checked with the user.
    static final int MAX_SAME_QUANTITY_DAYS = 3;

    private final DishRepository dishRepository;
    private final DishAliasRepository dishAliasRepository;
    private final DailySaleRepository dailySaleRepository;

    public ProposalValidator(DishRepository dishRepository, DishAliasRepository dishAliasRepository,
                             DailySaleRepository dailySaleRepository) {
        this.dishRepository = dishRepository;
        this.dishAliasRepository = dishAliasRepository;
        this.dailySaleRepository = dailySaleRepository;
    }

    public record Result(List<ProposedEntry> entries, List<String> problems) {
        public boolean isValid() {
            return problems.isEmpty();
        }
    }

    public Result validate(Long ownerId, List<ProposalEntryInput> inputs, LocalDate today) {
        return validate(ownerId, inputs, today, false);
    }

    public Result validateProposal(Long ownerId, List<ProposalEntryInput> inputs, LocalDate today) {
        return validate(ownerId, inputs, today, true);
    }

    private Result validate(Long ownerId, List<ProposalEntryInput> inputs, LocalDate today, boolean fillFromRecord) {
        List<String> problems = new ArrayList<>();
        List<ProposedEntry> entries = new ArrayList<>();

        if (inputs == null || inputs.isEmpty()) {
            return new Result(entries, List.of("There are no entries."));
        }
        if (inputs.size() > MAX_ENTRIES) {
            return new Result(entries, List.of("At most " + MAX_ENTRIES + " entries can be recorded at once."));
        }

        Set<String> seen = new HashSet<>();
        List<String> replaced = new ArrayList<>();

        for (ProposalEntryInput input : inputs) {
            String label = describe(input);
            Optional<Dish> dish = resolveDish(ownerId, input);

            if (dish.isEmpty() && input.dishId() != null) {
                problems.add(label + ": dish not found.");
                continue;
            }
            if (dish.isEmpty() && !isUsableName(input.newDishName())) {
                problems.add(label + ": needs a dish or a new dish name.");
                continue;
            }
            if (input.date() == null) {
                problems.add(label + ": needs a date.");
                continue;
            }
            if (input.date().isAfter(today)) {
                problems.add(label + ": " + input.date() + " is in the future.");
                continue;
            }
            if (input.date().isBefore(today.minusDays(MAX_DAYS_BACK))) {
                problems.add(label + ": " + input.date() + " is more than a year ago.");
                continue;
            }
            Optional<DailySale> recorded = dish.flatMap(d -> dailySaleRepository.findByDishIdAndSaleDate(d.getId(), input.date()));
            Integer quantity = input.quantity();
            LocalTime soldOutAt = input.soldOutAt();
            Integer leftoverQuantity = input.leftoverQuantity();
            if (fillFromRecord && recorded.isPresent()) {
                if (quantity == null) quantity = recorded.get().getQuantitySold();
                if (soldOutAt == null && (leftoverQuantity == null || leftoverQuantity == 0)) soldOutAt = recorded.get().getSoldOutAt();
                if (leftoverQuantity == null && soldOutAt == null) leftoverQuantity = recorded.get().getLeftoverQuantity();
            }

            if (quantity == null && (soldOutAt != null || leftoverQuantity != null)) {
                problems.add(label + ": no sales are recorded for " + input.date() + " yet, so the quantity sold is needed too.");
                continue;
            }
            if (quantity == null || quantity < 0 || quantity > MAX_QUANTITY) {
                problems.add(label + ": quantity must be between 0 and " + MAX_QUANTITY + ".");
                continue;
            }
            Optional<String> outcomeProblem = DailySaleService.outcomeProblem(soldOutAt, leftoverQuantity);
            if (outcomeProblem.isPresent()) {
                problems.add(label + ": " + outcomeProblem.get());
                continue;
            }

            if (fillFromRecord && input.quantity() != null) {
                Optional<String> implausible = implausibleQuantity(dish, label, input.date(), quantity);
                if (implausible.isPresent()) {
                    problems.add(implausible.get());
                    continue;
                }
                if (recorded.isPresent() && recorded.get().getQuantitySold() != quantity) {
                    replaced.add(label + " on " + input.date());
                }
            }

            String dishKey = dish.map(d -> "id:" + d.getId())
                    .orElseGet(() -> "new:" + DishAlias.normalize(input.newDishName()));
            if (!seen.add(dishKey + "@" + input.date())) {
                problems.add(label + ": " + input.date() + " is listed more than once.");
                continue;
            }

            entries.add(new ProposedEntry(
                    dish.map(Dish::getId).orElse(null),
                    dish.map(Dish::getName).orElseGet(() -> input.newDishName().strip()),
                    dish.isPresent() ? null : input.newDishName().strip(),
                    blankToNull(input.spokenName()),
                    input.date(),
                    quantity,
                    recorded.map(DailySale::getQuantitySold).orElse(null),
                    soldOutAt,
                    leftoverQuantity));
        }

        if (replaced.size() > MAX_REPLACED_DAYS) {
            problems.add("This would replace " + replaced.size() + " days that are already recorded (" + String.join(", ", replaced)
                    + "). Ask the user to confirm the number for each day, or to correct them on the Data page.");
        }
        return new Result(entries, problems);
    }

    // Compared with the dish's best day in the HISTORY_DAYS before the date.
    private Optional<String> implausibleQuantity(Optional<Dish> dish, String label, LocalDate date, int quantity) {
        List<Integer> recent = dish.map(d -> dailySaleRepository.findByDishIdOrderBySaleDateAsc(d.getId()).stream()
                        .filter(sale -> sale.getSaleDate().isBefore(date) && !sale.getSaleDate().isBefore(date.minusDays(HISTORY_DAYS)))
                        .map(DailySale::getQuantitySold)
                        .toList())
                .orElse(List.of());
        if (recent.size() < HISTORY_MIN_DAYS) {
            return quantity > NO_HISTORY_MAX
                    ? Optional.of(label + ": " + quantity + " portions is more than " + NO_HISTORY_MAX
                    + " in a day. Ask the user to check the number.")
                    : Optional.empty();
        }
        int best = Collections.max(recent);
        return quantity > Math.max(best * HISTORY_FACTOR, best + HISTORY_MARGIN)
                ? Optional.of(label + ": " + quantity + " portions is far above its best day in the last " + HISTORY_DAYS
                + " days (" + best + "). Ask the user to check the number.")
                : Optional.empty();
    }

    // The same dish with the same quantity on several dates in one proposal, e.g. 999 for every
    // day this month. It can be true (a stall that cooks a fixed amount and sells out), so the
    // tool asks the model to check with the user rather than refusing it.
    public record RepeatedQuantity(String dishName, int quantity, List<LocalDate> dates, String key) {}

    public static List<RepeatedQuantity> repeatedQuantities(List<ProposedEntry> entries) {
        Map<String, List<ProposedEntry>> groups = new LinkedHashMap<>();
        for (ProposedEntry entry : entries) {
            String dish = entry.dishId() != null ? "id:" + entry.dishId() : "new:" + DishAlias.normalize(entry.dishName());
            groups.computeIfAbsent(dish + "x" + entry.quantity(), key -> new ArrayList<>()).add(entry);
        }
        List<RepeatedQuantity> repeated = new ArrayList<>();
        groups.forEach((group, sameQuantity) -> {
            List<LocalDate> dates = sameQuantity.stream().map(ProposedEntry::date).distinct().sorted().toList();
            if (dates.size() > MAX_SAME_QUANTITY_DAYS) {
                String key = group + "@" + dates.stream().map(LocalDate::toString).reduce((a, b) -> a + "," + b).orElse("");
                repeated.add(new RepeatedQuantity(sameQuantity.getFirst().dishName(), sameQuantity.getFirst().quantity(), dates, key));
            }
        });
        return repeated;
    }

    // A "new" dish whose name the user already has (as a dish or an alias) is the existing dish,
    // so confirming twice or a model slip never creates duplicates.
    private Optional<Dish> resolveDish(Long ownerId, ProposalEntryInput input) {
        if (input.dishId() != null) {
            return dishRepository.findByIdAndOwnerId(input.dishId(), ownerId);
        }
        if (!isUsableName(input.newDishName())) {
            return Optional.empty();
        }

        String normalized = DishAlias.normalize(input.newDishName());
        return dishRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .filter(dish -> DishAlias.normalize(dish.getName()).equals(normalized))
                .findFirst()
                .or(() -> dishAliasRepository.findByOwnerIdAndNormalizedAlias(ownerId, normalized).map(DishAlias::getDish));
    }

    private static boolean isUsableName(String name) {
        return name != null && !name.isBlank() && name.strip().length() <= MAX_NAME_LENGTH;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String describe(ProposalEntryInput input) {
        if (input.newDishName() != null && !input.newDishName().isBlank()) return input.newDishName().strip();
        if (input.spokenName() != null && !input.spokenName().isBlank()) return input.spokenName().strip();
        return input.dishId() == null ? "Entry" : "Dish " + input.dishId();
    }
}
