package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.ForecastResponse;
import app.dto.PrepListResponse;
import app.exception.InvalidForecastDateException;
import app.recipe.RecipeService;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
@Service
public class PrepListService {

    // Dishes not sold in this many days are taken to be off the menu and left out.
    static final int RECENT_DAYS = 28;
    static final int MAX_BUFFER_PERCENT = 100;

    private final ForecastService forecastService;
    private final StallClosureService stallClosureService;
    private final RecipeService recipeService;
    private final DishRepository dishRepository;
    private final DailySaleRepository dailySaleRepository;
    private final Clock clock;

    public PrepListService(ForecastService forecastService, StallClosureService stallClosureService,
                           RecipeService recipeService, DishRepository dishRepository,
                           DailySaleRepository dailySaleRepository, Clock clock) {
        this.forecastService = forecastService;
        this.stallClosureService = stallClosureService;
        this.recipeService = recipeService;
        this.dishRepository = dishRepository;
        this.dailySaleRepository = dailySaleRepository;
        this.clock = clock;
    }

    // bufferPercent null: prepare the top of each dish's likely range. Otherwise prepare the
    // forecast plus that many percent.
    public PrepListResponse prepList(Long ownerId, LocalDate date, Integer bufferPercent) {
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

        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            if (!recentlySold.contains(dish.getId())) {
                continue;
            }
            ForecastResponse forecast;
            try {
                forecast = forecastService.forecast(dish.getId(), date, ownerId);
            } catch (IllegalArgumentException exception) {
                skipped.add(new PrepListResponse.Skipped(dish.getName(), exception.getMessage()));
                continue;
            }

            int portions = bufferPercent == null
                    ? forecast.high()
                    : (int) Math.ceil(forecast.predictedDemand() * (100 + bufferPercent) / 100.0);
            List<RecipeService.Line> recipe = recipes.getOrDefault(dish.getId(), List.of());
            dishes.add(new PrepListResponse.Dish(dish.getId(), dish.getName(), portions,
                    Math.round(forecast.predictedDemand() * 10) / 10.0, forecast.low(), forecast.high(),
                    forecast.method(), forecast.basis(), !recipe.isEmpty()));

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
}
