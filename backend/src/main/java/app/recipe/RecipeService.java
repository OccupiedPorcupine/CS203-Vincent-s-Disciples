package app.recipe;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.IngredientResponse;
import app.dto.RecipeItemInput;
import app.dto.RecipeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// Each dish's recipe: what one portion uses. Ingredients are created by naming them in a recipe
// and removed when no recipe uses them, so the owner only ever edits recipes.
@Service
public class RecipeService {

    private final DishIngredientRepository dishIngredients;
    private final IngredientRepository ingredients;
    private final DishRepository dishRepository;
    private final Clock clock;

    public RecipeService(DishIngredientRepository dishIngredients, IngredientRepository ingredients,
                         DishRepository dishRepository, Clock clock) {
        this.dishIngredients = dishIngredients;
        this.ingredients = ingredients;
        this.dishRepository = dishRepository;
        this.clock = clock;
    }

    // Every dish, with or without a recipe, sorted by name.
    @Transactional(readOnly = true)
    public List<RecipeResponse> recipes(Long ownerId) {
        Map<Long, List<DishIngredient>> byDish = dishIngredients.findByOwnerId(ownerId).stream()
                .collect(Collectors.groupingBy(item -> item.getDish().getId()));
        return dishRepository.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .map(dish -> toResponse(dish, byDish.getOrDefault(dish.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<IngredientResponse> ingredients(Long ownerId) {
        return ingredients.findByOwnerIdOrderByNameAsc(ownerId).stream()
                .map(ingredient -> new IngredientResponse(ingredient.getName(), ingredient.getUnit()))
                .toList();
    }

    // One line of a recipe, detached from the database so it can be used outside a transaction.
    public record Line(Long ingredientId, String ingredient, String unit, BigDecimal quantity) {}

    // Every recipe of the owner's, by dish ID.
    @Transactional(readOnly = true)
    public Map<Long, List<Line>> lines(Long ownerId) {
        return dishIngredients.findByOwnerId(ownerId).stream()
                .collect(Collectors.groupingBy(item -> item.getDish().getId(), Collectors.mapping(item -> new Line(
                        item.getIngredient().getId(), item.getIngredient().getName(), item.getIngredient().getUnit(),
                        item.getQuantity()), Collectors.toList())));
    }

    // Replaces a dish's recipe. An ingredient keeps one unit across dishes; its unit can only be
    // changed here when no other dish uses it, since their amounts would otherwise be misread.
    @Transactional
    public RecipeResponse setRecipe(Long ownerId, Long dishId, List<RecipeItemInput> items) {
        Dish dish = dishRepository.findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Dish not found"));

        Map<String, RecipeItemInput> wanted = new LinkedHashMap<>();
        for (RecipeItemInput item : items) {
            String name = item.ingredient().strip().replaceAll("\\s+", " ");
            if (wanted.putIfAbsent(Ingredient.key(name), new RecipeItemInput(name, item.unit().strip(), item.quantity())) != null) {
                throw new IllegalArgumentException(name + " is listed twice. Put its whole amount on one line.");
            }
        }

        List<DishIngredient> current = dishIngredients.findByDishId(dishId);
        Map<Long, DishIngredient> currentByIngredient = new HashMap<>();
        current.forEach(item -> currentByIngredient.put(item.getIngredient().getId(), item));

        List<DishIngredient> kept = new ArrayList<>();
        for (RecipeItemInput item : wanted.values()) {
            Ingredient ingredient = ingredientFor(ownerId, dish, item);
            DishIngredient existing = currentByIngredient.get(ingredient.getId());
            if (existing != null) {
                existing.setQuantity(item.quantity());
                kept.add(existing);
            } else {
                kept.add(dishIngredients.save(new DishIngredient(dish, ingredient, item.quantity())));
            }
        }

        List<DishIngredient> removed = current.stream().filter(item -> !kept.contains(item)).toList();
        dishIngredients.deleteAll(removed);
        dishIngredients.flush();
        removed.stream().map(DishIngredient::getIngredient).distinct()
                .filter(ingredient -> dishIngredients.findByIngredientId(ingredient.getId()).isEmpty())
                .forEach(ingredients::delete);

        return toResponse(dish, kept);
    }

    private Ingredient ingredientFor(Long ownerId, Dish dish, RecipeItemInput item) {
        Optional<Ingredient> found = ingredients.findByOwnerIdAndNameKey(ownerId, Ingredient.key(item.ingredient()));
        if (found.isEmpty()) {
            return ingredients.save(new Ingredient(dish.getOwner(), item.ingredient(), item.unit(), clock.instant()));
        }

        Ingredient ingredient = found.get();
        if (!ingredient.getUnit().equalsIgnoreCase(item.unit())) {
            Set<String> others = dishIngredients.findByIngredientId(ingredient.getId()).stream()
                    .map(DishIngredient::getDish)
                    .filter(other -> !other.getId().equals(dish.getId()))
                    .map(Dish::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!others.isEmpty()) {
                throw new IllegalArgumentException(ingredient.getName() + " is measured in " + ingredient.getUnit()
                        + " in " + String.join(", ", others) + ". Use " + ingredient.getUnit() + " here too.");
            }
            ingredient.setUnit(item.unit());
        }
        return ingredient;
    }

    private static RecipeResponse toResponse(Dish dish, List<DishIngredient> items) {
        return new RecipeResponse(dish.getId(), dish.getName(), items.stream()
                .sorted(Comparator.comparing((DishIngredient item) -> item.getIngredient().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(item -> new RecipeResponse.Item(item.getIngredient().getName(), item.getIngredient().getUnit(),
                        plain(item.getQuantity())))
                .toList());
    }

    // 150.000 as 150 and 0.250 as 0.25, never 1.5E+2.
    public static BigDecimal plain(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
