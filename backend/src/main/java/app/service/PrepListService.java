package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.ForecastResponse;
import app.dto.PrepListResponse;
import app.exception.InvalidForecastDateException;
import app.price.DishPriceService;
import app.price.PriceBook;
import app.recipe.RecipeService;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// What to prepare for a day (issue #45): portions of each dish the stall has sold recently, and
// the ingredients those portions need according to the recipes.
//
// By default each dish is prepared to its margin (issue #86, the newsvendor rule). Running out
// loses a plate's profit (price − cost); a leftover loses its cost. Preparing enough for
// (price − cost) / price of likely days balances the two: 64% of days for a $4.50 plate that
// costs $1.60. In the prep simulator (ml/README.md) this earns more than the top of the range
// or the forecast + 20%, with fewer leftovers. Dishes without a price and cost get the top of
// the likely range instead.
@Service
public class PrepListService {

    // Dishes not sold in this many days are taken to be off the menu and left out.
    static final int RECENT_DAYS = 28;
    static final int MAX_BUFFER_PERCENT = 100;
    // Never aim below or above these shares of days, even for a dish that barely pays or costs nothing.
    static final double MIN_SHARE = 0.01;
    static final double MAX_SHARE = 0.99;

    private final ForecastService forecastService;
    private final StallClosureService stallClosureService;
    private final RecipeService recipeService;
    private final DishRepository dishRepository;
    private final DailySaleRepository dailySaleRepository;
    private final DishPriceService priceService;
    private final Clock clock;

    public PrepListService(ForecastService forecastService, StallClosureService stallClosureService,
                           RecipeService recipeService, DishRepository dishRepository,
                           DailySaleRepository dailySaleRepository, DishPriceService priceService, Clock clock) {
        this.forecastService = forecastService;
        this.stallClosureService = stallClosureService;
        this.recipeService = recipeService;
        this.dishRepository = dishRepository;
        this.dailySaleRepository = dailySaleRepository;
        this.priceService = priceService;
        this.clock = clock;
    }

    public enum Rule { MARGIN, TOP_OF_RANGE, BUFFER }

    public PrepListResponse prepList(Long ownerId, LocalDate date, Integer bufferPercent) {
        return prepList(ownerId, date, bufferPercent, false);
    }

    // bufferPercent given: the forecast plus that many percent. Otherwise each dish's margin, or the
    // top of its likely range when topOfRange is true or the dish has no price and cost.
    public PrepListResponse prepList(Long ownerId, LocalDate date, Integer bufferPercent, boolean topOfRange) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(ForecastService.MAX_DAYS_AHEAD))) {
            throw new InvalidForecastDateException("Choose a date from today up to " + ForecastService.MAX_DAYS_AHEAD + " days ahead.");
        }
        if (bufferPercent != null && (bufferPercent < 0 || bufferPercent > MAX_BUFFER_PERCENT)) {
            throw new IllegalArgumentException("The safety buffer must be from 0 to " + MAX_BUFFER_PERCENT + "%.");
        }

        Optional<String> closed = stallClosureService.closureMessage(ownerId, date);
        if (closed.isPresent()) {
            return new PrepListResponse(date, closed.get(), bufferPercent, List.of(), List.of(), List.of());
        }

        Set<Long> recentlySold = dailySaleRepository.findByDishOwnerIdAndSaleDateBetween(ownerId, today.minusDays(RECENT_DAYS), today)
                .stream().map(sale -> sale.getDish().getId()).collect(Collectors.toSet());
        Map<Long, List<RecipeService.Line>> recipes = recipeService.lines(ownerId);

        List<PrepListResponse.Dish> dishes = new ArrayList<>();
        List<PrepListResponse.Skipped> skipped = new ArrayList<>();
        Map<Long, RecipeService.Line> ingredientsById = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        Map<Long, Set<String>> usedBy = new LinkedHashMap<>();
        PriceBook prices = priceService.priceBook(ownerId);

        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            if (!recentlySold.contains(dish.getId())) {
                continue;
            }
            ForecastService.Prediction prediction;
            try {
                prediction = forecastService.predict(dish.getId(), date, ownerId);
            } catch (IllegalArgumentException exception) {
                skipped.add(new PrepListResponse.Skipped(dish.getName(), exception.getMessage()));
                continue;
            }
            ForecastResponse forecast = prediction.response();
            PriceBook.Price price = prices.on(dish.getId(), date)
                    .filter(known -> known.unitCost() != null && known.price().signum() > 0)
                    .orElse(null);

            int portions;
            Rule rule;
            String why;
            if (bufferPercent != null) {
                portions = (int) Math.ceil(forecast.predictedDemand() * (100 + bufferPercent) / 100.0);
                rule = Rule.BUFFER;
                why = "The forecast plus " + bufferPercent + "%.";
            } else if (topOfRange || price == null) {
                portions = forecast.high();
                rule = Rule.TOP_OF_RANGE;
                why = topOfRange ? "The top of the likely range."
                        : "The top of the likely range. Set this dish's price and cost on the Dishes page to prepare it by its margin.";
            } else {
                BigDecimal profit = price.price().subtract(price.unitCost());
                double share = Math.min(MAX_SHARE, Math.max(MIN_SHARE,
                        profit.doubleValue() / price.price().doubleValue()));
                portions = (int) Math.ceil(prediction.atProbability(share) - 1e-9);
                rule = Rule.MARGIN;
                why = "Running out costs you " + money(profit.max(BigDecimal.ZERO)) + " a plate and a leftover "
                        + money(price.unitCost()) + ", so this is enough on about " + Math.round(share * 100) + "% of days like it.";
            }

            List<RecipeService.Line> recipe = recipes.getOrDefault(dish.getId(), List.of());
            dishes.add(new PrepListResponse.Dish(dish.getId(), dish.getName(), portions,
                    Math.round(forecast.predictedDemand() * 10) / 10.0, forecast.low(), forecast.high(),
                    forecast.method(), forecast.basis(), !recipe.isEmpty(), rule, why));

            for (RecipeService.Line line : recipe) {
                ingredientsById.put(line.ingredientId(), line);
                totals.merge(line.ingredientId(), line.quantity().multiply(BigDecimal.valueOf(portions)), BigDecimal::add);
                usedBy.computeIfAbsent(line.ingredientId(), id -> new TreeSet<>()).add(dish.getName());
            }
        }

        List<PrepListResponse.Ingredient> ingredients = ingredientsById.values().stream()
                .sorted(Comparator.comparing(RecipeService.Line::ingredient, String.CASE_INSENSITIVE_ORDER))
                .map(line -> new PrepListResponse.Ingredient(line.ingredient(), line.unit(),
                        RecipeService.plain(totals.get(line.ingredientId())), List.copyOf(usedBy.get(line.ingredientId()))))
                .toList();
        return new PrepListResponse(date, null, bufferPercent, dishes, ingredients, skipped);
    }

    private static String money(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
