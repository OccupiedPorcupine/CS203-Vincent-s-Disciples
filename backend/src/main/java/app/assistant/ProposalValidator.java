package app.assistant;

import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.ProposalEntryInput;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

// Checks sales entries from the model (before they are shown) and from the user (before they
// are saved). The model is never trusted: dishes must belong to the user, dates can't be in the
// future, and quantities must be plausible.
@Component
public class ProposalValidator {

    public static final int MAX_QUANTITY = 9999;
    public static final int MAX_DAYS_BACK = 365;
    public static final int MAX_ENTRIES = 50;
    private static final int MAX_NAME_LENGTH = 100;

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
        List<String> problems = new ArrayList<>();
        List<ProposedEntry> entries = new ArrayList<>();

        if (inputs == null || inputs.isEmpty()) {
            return new Result(entries, List.of("There are no entries."));
        }
        if (inputs.size() > MAX_ENTRIES) {
            return new Result(entries, List.of("At most " + MAX_ENTRIES + " entries can be recorded at once."));
        }

        Set<String> seen = new HashSet<>();

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
            if (input.quantity() == null || input.quantity() < 0 || input.quantity() > MAX_QUANTITY) {
                problems.add(label + ": quantity must be between 0 and " + MAX_QUANTITY + ".");
                continue;
            }

            String dishKey = dish.map(d -> "id:" + d.getId())
                    .orElseGet(() -> "new:" + DishAlias.normalize(input.newDishName()));
            if (!seen.add(dishKey + "@" + input.date())) {
                problems.add(label + ": " + input.date() + " is listed more than once.");
                continue;
            }

            Integer previousQuantity = dish
                    .flatMap(d -> dailySaleRepository.findByDishIdAndSaleDate(d.getId(), input.date()))
                    .map(DailySale::getQuantitySold)
                    .orElse(null);

            entries.add(new ProposedEntry(
                    dish.map(Dish::getId).orElse(null),
                    dish.map(Dish::getName).orElseGet(() -> input.newDishName().strip()),
                    dish.isPresent() ? null : input.newDishName().strip(),
                    blankToNull(input.spokenName()),
                    input.date(),
                    input.quantity(),
                    previousQuantity));
        }

        return new Result(entries, problems);
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
