package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.ForecastResponse;
import app.dto.PrepListResponse;
import app.dto.PrepListResponse.Rule;
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

// What to prepare for a day (issue #45): portions of each dish the stall has sold recently, from
// its forecast plus a margin, and the ingredients those portions need according to the recipes.
//
// By default each dish is prepared by the margin rule (#86), the newsvendor rule for perishables:
// a portion that runs out loses its profit (price - cost) and a leftover loses its cost, so
// prepare up to the point demand stays under with probability (price - cost) / price. Chicken
// rice at $4.50 costing $1.60 is prepared to its 64th percentile. In the prep simulator
// (ml/README.md) this earned the most profit of the rules the app offers, with far fewer leftovers
// but more sell-outs, so the page explains it and keeps the other rules one tap away.
@Service
public class PrepListService {

    // Dishes not sold in this many days are taken to be off the menu and left out.
    static final int RECENT_DAYS = 28;
    static final int MAX_BUFFER_PERCENT = 100;

    private final ForecastService forecastService;
    private final DishPriceService priceService;
    private final StallClosureService stallClosureService;
    private final RecipeService recipeService;
    private final DishRepository dishRepository;
    private final DailySaleRepository dailySaleRepository;
    private final Clock clock;

    public PrepListService(ForecastService forecastService, DishPriceService priceService,
                           StallClosureService stallClosureService, RecipeService recipeService,
                           DishRepository dishRepository, DailySaleRepository dailySaleRepository, Clock clock) {
        this.forecastService = forecastService;
        this.priceService = priceService;
        this.stallClosureService = stallClosureService;
        this.recipeService = recipeService;
        this.dishRepository = dishRepository;
        this.dailySaleRepository = dailySaleRepository;
        this.clock = clock;
    }

    // With bufferPercent, every dish gets the forecast plus that many percent. Otherwise the rule
    // applies: MARGIN (the default, when rule is null) or RANGE.
    public PrepListResponse prepList(Long ownerId, LocalDate date, Rule rule, Integer bufferPercent) {
        LocalDate today = LocalDate.now(clock);
        if (date.isBefore(today) || date.isAfter(today.plusDays(ForecastService.MAX_DAYS_AHEAD))) {
            throw new InvalidForecastDateException("Choose a date from today up to " + ForecastService.MAX_DAYS_AHEAD + " days ahead.");
        }
        if (bufferPercent != null && (bufferPercent < 0 || bufferPercent > MAX_BUFFER_PERCENT)) {
            throw new IllegalArgumentException("The safety buffer must be from 0 to " + MAX_BUFFER_PERCENT + "%.");
        }

        Rule asked = bufferPercent != null ? Rule.BUFFER : rule == null ? Rule.MARGIN : rule;
        if (asked == Rule.BUFFER && bufferPercent == null) {
            throw new IllegalArgumentException("Give bufferPercent for the BUFFER rule.");
        }

        Optional<String> closed = stallClosureService.closureMessage(ownerId, date);
        if (closed.isPresent()) {
            return new PrepListResponse(date, closed.get(), asked, bufferPercent, List.of(), List.of(), List.of());
        }

        Set<Long> recentlySold = dailySaleRepository.findByDishOwnerIdAndSaleDateBetween(ownerId, today.minusDays(RECENT_DAYS), today)
                .stream().map(sale -> sale.getDish().getId()).collect(Collectors.toSet());
        Map<Long, List<RecipeService.Line>> recipes = recipeService.lines(ownerId);
        PriceBook prices = priceService.priceBook(ownerId);

        List<PrepListResponse.Dish> dishes = new ArrayList<>();
        List<PrepListResponse.Skipped> skipped = new ArrayList<>();
        Map<Long, RecipeService.Line> ingredientsById = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        Map<Long, Set<String>> usedBy = new LinkedHashMap<>();

        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            if (!recentlySold.contains(dish.getId())) {
                continue;
            }
            ForecastService.WithHistory result;
            try {
                result = forecastService.forecastWithHistory(dish.getId(), date, ownerId);
            } catch (IllegalArgumentException exception) {
                skipped.add(new PrepListResponse.Skipped(dish.getName(), exception.getMessage()));
                continue;
            }
            ForecastResponse forecast = result.forecast();

            Choice choice = choose(asked, bufferPercent, result, prices.on(dish.getId(), date));
            int portions = choice.portions();
            List<RecipeService.Line> recipe = recipes.getOrDefault(dish.getId(), List.of());
            dishes.add(new PrepListResponse.Dish(dish.getId(), dish.getName(), portions,
                    Math.round(forecast.predictedDemand() * 10) / 10.0, forecast.low(), forecast.high(),
                    forecast.method(), forecast.basis(), !recipe.isEmpty(), choice.rule(), choice.reason()));

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
        return new PrepListResponse(date, null, asked, bufferPercent, dishes, ingredients, skipped);
    }

    record Choice(int portions, Rule rule, String reason) {}

    static Choice choose(Rule asked, Integer bufferPercent, ForecastService.WithHistory result, Optional<PriceBook.Price> price) {
        ForecastResponse forecast = result.forecast();
        if (asked == Rule.BUFFER) {
            return new Choice((int) Math.ceil(forecast.predictedDemand() * (100 + bufferPercent) / 100.0), Rule.BUFFER, null);
        }
        if (asked == Rule.RANGE) {
            return new Choice(forecast.high(), Rule.RANGE, null);
        }

        BigDecimal sells = price.map(PriceBook.Price::price).orElse(null);
        BigDecimal costs = price.map(PriceBook.Price::unitCost).orElse(null);
        if (sells == null || costs == null) {
            return new Choice(forecast.high(), Rule.RANGE,
                    "Top of the likely range. Add a price and cost on the Dishes page to prepare by profit instead.");
        }
        BigDecimal profit = sells.subtract(costs);
        if (profit.signum() <= 0) {
            return new Choice(forecast.high(), Rule.RANGE,
                    "Top of the likely range: its cost isn't below its price. Check them on the Dishes page.");
        }

        double ratio = Math.min(0.99, Math.max(0.01, profit.doubleValue() / sells.doubleValue()));
        double quantity = BaselineForecaster.quantile(result.recorded(), forecast.predictedDemand(), ratio);
        return new Choice((int) Math.ceil(quantity - 1e-9), Rule.MARGIN,
                "Enough for about " + Math.round(ratio * 100) + "% of days: running out loses " + dollars(profit)
                        + " profit a plate, a leftover wastes " + dollars(costs) + ".");
    }

    private static String dollars(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
