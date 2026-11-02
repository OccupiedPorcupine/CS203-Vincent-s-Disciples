package app.salesimport;

import app.datasource.TrainingDataSource;
import app.datasource.TrainingDataSourceRepository;
import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.SalesImportPreviewResponse;
import app.dto.SalesImportRequest;
import app.dto.SalesImportResponse;
import app.price.DishPriceService;
import app.salesimport.SalesFileReader.RowError;
import app.salesimport.SalesFileReader.SalesFile;
import app.salesimport.SalesFileReader.SalesRow;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import app.service.DailySaleService;
import app.service.DishAliasService;
import app.service.DishService;
import app.service.TrainingDataSourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

// Turns an uploaded sales file (Data page) into daily sales. The preview says what would happen;
// the import writes it in one transaction. Names in the file are matched to the owner's dishes
// by name or nickname; the owner decides what happens to the rest.
@Service
public class SalesImportService {

    private static final Logger log = LoggerFactory.getLogger(SalesImportService.class);

    private final TrainingDataSourceRepository sources;
    private final TrainingDataSourceService sourceService;
    private final DishRepository dishRepository;
    private final DishAliasRepository aliasRepository;
    private final DishService dishService;
    private final DishAliasService dishAliasService;
    private final DailySaleService dailySaleService;
    private final DailySaleRepository saleRepository;
    private final DishPriceService priceService;
    private final Clock clock;

    public SalesImportService(TrainingDataSourceRepository sources, TrainingDataSourceService sourceService,
                              DishRepository dishRepository, DishAliasRepository aliasRepository,
                              DishService dishService, DishAliasService dishAliasService,
                              DailySaleService dailySaleService, DailySaleRepository saleRepository,
                              DishPriceService priceService, Clock clock) {
        this.sources = sources;
        this.sourceService = sourceService;
        this.dishRepository = dishRepository;
        this.aliasRepository = aliasRepository;
        this.dishService = dishService;
        this.dishAliasService = dishAliasService;
        this.dailySaleService = dailySaleService;
        this.saleRepository = saleRepository;
        this.priceService = priceService;
        this.clock = clock;
    }

    // The rows for one name in the file. Spellings that differ only in case or spacing are one name.
    private record NameInFile(String name, List<SalesRow> rows) {}

    private record MatchedDish(Dish dish, String by) {}

    @Transactional(readOnly = true)
    public SalesImportPreviewResponse preview(Long ownerId, Long sourceId) {
        TrainingDataSource source = findSource(ownerId, sourceId);
        SalesFile file = read(source);
        Map<String, NameInFile> names = groupByName(file.rows());
        Map<String, MatchedDish> matches = match(ownerId, names.keySet());
        Map<String, SalesFileReader.FilePrice> prices = filePrices(file);

        Map<Long, Set<LocalDate>> salesDates = new HashMap<>();
        List<SalesImportPreviewResponse.DishInFile> dishes = new ArrayList<>();
        int rowsWithExistingSales = 0;
        for (Map.Entry<String, NameInFile> entry : names.entrySet()) {
            NameInFile name = entry.getValue();
            MatchedDish matched = matches.get(entry.getKey());
            int existing = 0;
            if (matched != null) {
                Set<LocalDate> dates = salesDates.computeIfAbsent(matched.dish().getId(), this::datesWithSales);
                existing = (int) name.rows().stream().map(SalesRow::date).distinct().filter(dates::contains).count();
            }
            rowsWithExistingSales += existing;
            SalesFileReader.FilePrice price = prices.get(entry.getKey());
            dishes.add(new SalesImportPreviewResponse.DishInFile(
                    name.name(),
                    name.rows().size(),
                    name.rows().stream().mapToLong(SalesRow::quantity).sum(),
                    matched == null ? null : new SalesImportPreviewResponse.Match(
                            matched.dish().getId(), matched.dish().getName(), matched.by()),
                    existing,
                    price == null ? null : price.price(),
                    price == null ? null : price.unitCost()));
        }

        // Rows that land on the same dish and day are added together: a name repeated on one day,
        // or two names that match the same dish.
        Map<Object, Set<LocalDate>> datesByTarget = new HashMap<>();
        names.forEach((key, name) -> {
            MatchedDish matched = matches.get(key);
            Object target = matched == null ? key : matched.dish().getId();
            name.rows().forEach(row -> datesByTarget.computeIfAbsent(target, ignored -> new HashSet<>()).add(row.date()));
        });
        int distinctDishDays = datesByTarget.values().stream().mapToInt(Set::size).sum();

        LocalDate firstDate = file.rows().stream().map(SalesRow::date).min(Comparator.naturalOrder()).orElse(null);
        LocalDate lastDate = file.rows().stream().map(SalesRow::date).max(Comparator.naturalOrder()).orElse(null);
        return new SalesImportPreviewResponse(
                source.getId(), source.getOriginalFilename(), file.sheet(), file.rows().size(), firstDate, lastDate,
                dishes, rowsWithExistingSales, file.rows().size() - distinctDishDays,
                file.errorCount(), file.errors(), sourceService.toResponse(source).lastImport());
    }

    @Transactional
    public SalesImportResponse importSales(Long ownerId, Long sourceId, SalesImportRequest request) {
        TrainingDataSource source = findSource(ownerId, sourceId);
        SalesFile file = read(source);
        if (file.errorCount() > 0) {
            throw new SalesImportException("IMPORT_INVALID",
                    file.errorCount() + (file.errorCount() == 1 ? " row has a problem" : " rows have problems")
                            + ". Fix the file, upload it again and import that.",
                    file.errorCount(), file.errors());
        }
        if (file.rows().isEmpty()) {
            throw new IllegalArgumentException("The file has no sales rows.");
        }

        Map<String, NameInFile> names = groupByName(file.rows());
        Map<String, MatchedDish> matches = match(ownerId, names.keySet());
        Map<String, SalesImportRequest.DishChoice> choices = new HashMap<>();
        if (request.dishes() != null) {
            request.dishes().forEach(choice -> choices.put(DishAlias.normalize(choice.name()), choice));
        }
        Map<String, Dish> dishesByName = new HashMap<>();
        dishRepository.findByOwnerIdOrderByNameAsc(ownerId)
                .forEach(dish -> dishesByName.putIfAbsent(DishAlias.normalize(dish.getName()), dish));

        Map<String, Dish> targets = new LinkedHashMap<>();
        List<SalesImportResponse.CreatedDish> createdDishes = new ArrayList<>();
        List<RowError> unmatched = new ArrayList<>();
        int rowsLeftOut = 0;
        for (Map.Entry<String, NameInFile> entry : names.entrySet()) {
            String key = entry.getKey();
            NameInFile name = entry.getValue();
            SalesImportRequest.DishChoice choice = choices.get(key);

            if (choice == null) {
                MatchedDish matched = matches.get(key);
                if (matched == null) {
                    unmatched.add(new RowError(null, "dish", "\"" + name.name() + "\" isn't one of your dishes."));
                } else {
                    targets.put(key, matched.dish());
                }
                continue;
            }

            switch (choice.action()) {
                case EXISTING -> {
                    if (choice.dishId() == null) {
                        throw new IllegalArgumentException("Choose which dish \"" + name.name() + "\" is.");
                    }
                    Dish dish = dishRepository.findByIdAndOwnerId(choice.dishId(), ownerId)
                            .orElseThrow(() -> new IllegalArgumentException("Dish not found"));
                    targets.put(key, dish);
                    // So the next import, and the voice assistant, recognise this name.
                    dishAliasService.rememberAlias(dish, name.name());
                }
                case NEW -> {
                    String newName = choice.newDishName() == null || choice.newDishName().isBlank()
                            ? name.name() : choice.newDishName().strip();
                    String newKey = DishAlias.normalize(newName);
                    Dish dish = dishesByName.get(newKey);
                    if (dish != null && createdDishes.stream().noneMatch(created -> created.id().equals(dish.getId()))) {
                        throw new IllegalArgumentException("There's already a dish called " + dish.getName()
                                + ". Choose it instead of adding a new one.");
                    }
                    if (dish == null) {
                        Dish created = dishService.createDish(newName, ownerId);
                        dishesByName.put(newKey, created);
                        createdDishes.add(new SalesImportResponse.CreatedDish(created.getId(), created.getName()));
                        targets.put(key, created);
                    } else {
                        targets.put(key, dish); // two names added as the same new dish
                    }
                    if (!newKey.equals(key)) {
                        dishAliasService.rememberAlias(targets.get(key), name.name());
                    }
                }
                case LEAVE_OUT -> rowsLeftOut += name.rows().size();
            }
        }
        if (!unmatched.isEmpty()) {
            throw new SalesImportException("UNMATCHED_DISHES",
                    "Some names in the file don't match any of your dishes. For each one, choose a dish, "
                            + "add it as a new dish, or leave it out.",
                    unmatched.size(), unmatched);
        }

        // Rows for the same dish and day are added together.
        Map<Long, Dish> dishesById = new LinkedHashMap<>();
        Map<Long, Map<LocalDate, Integer>> quantities = new LinkedHashMap<>();
        targets.forEach((key, dish) -> {
            dishesById.put(dish.getId(), dish);
            Map<LocalDate, Integer> byDate = quantities.computeIfAbsent(dish.getId(), ignored -> new TreeMap<>());
            names.get(key).rows().forEach(row -> byDate.merge(row.date(), row.quantity(), Integer::sum));
        });

        // Prices from the file, only for dishes without one, from the first day of their sales.
        Map<String, SalesFileReader.FilePrice> prices = filePrices(file);
        int pricesSet = 0;
        for (Map.Entry<String, Dish> entry : targets.entrySet()) {
            SalesFileReader.FilePrice price = prices.get(entry.getKey());
            if (price == null) continue;
            LocalDate firstDay = names.get(entry.getKey()).rows().stream().map(SalesRow::date).min(Comparator.naturalOrder()).orElseThrow();
            if (priceService.setPriceIfMissing(entry.getValue(), firstDay, price.price(), price.unitCost())) pricesSet++;
        }

        boolean overwrite = request.existingSales() == SalesImportRequest.ExistingSales.OVERWRITE;
        int added = 0, overwritten = 0, skipped = 0;
        for (Map.Entry<Long, Map<LocalDate, Integer>> entry : quantities.entrySet()) {
            DailySaleService.ImportResult result =
                    dailySaleService.importSales(dishesById.get(entry.getKey()), entry.getValue(), overwrite);
            added += result.added();
            overwritten += result.overwritten();
            skipped += result.skipped();
        }

        source.recordImport(clock.instant(), added, overwritten, skipped);
        log.info("Imported source {} for user {}: {} added, {} overwritten, {} skipped, {} left out, {} new dishes, {} prices set",
                sourceId, ownerId, added, overwritten, skipped, rowsLeftOut, createdDishes.size(), pricesSet);
        return new SalesImportResponse(added, overwritten, skipped, rowsLeftOut, createdDishes, pricesSet,
                sourceService.toResponse(source));
    }

    // Keyed by normalised name, like the sales rows.
    private static Map<String, SalesFileReader.FilePrice> filePrices(SalesFile file) {
        Map<String, SalesFileReader.FilePrice> prices = new HashMap<>();
        file.prices().forEach((name, price) -> prices.put(DishAlias.normalize(name), price));
        return prices;
    }

    private TrainingDataSource findSource(Long ownerId, Long sourceId) {
        return sources.findByIdAndOwnerId(sourceId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Uploaded file not found"));
    }

    private SalesFile read(TrainingDataSource source) {
        Path path = Path.of(source.getStoredPath());
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("The uploaded file is no longer on the server. Please upload it again.");
        }
        try {
            return SalesFileReader.read(path, source.getOriginalFilename(), LocalDate.now(clock));
        } catch (IOException exception) {
            throw new IllegalStateException("The uploaded file could not be read", exception);
        }
    }

    private static Map<String, NameInFile> groupByName(List<SalesRow> rows) {
        Map<String, NameInFile> names = new LinkedHashMap<>();
        for (SalesRow row : rows) {
            names.computeIfAbsent(DishAlias.normalize(row.dish()), ignored -> new NameInFile(row.dish(), new ArrayList<>()))
                    .rows().add(row);
        }
        return names;
    }

    // Keyed by normalised name; names that match no dish or nickname are missing.
    private Map<String, MatchedDish> match(Long ownerId, Collection<String> normalizedNames) {
        Map<String, Dish> byName = new HashMap<>();
        dishRepository.findByOwnerIdOrderByNameAsc(ownerId)
                .forEach(dish -> byName.putIfAbsent(DishAlias.normalize(dish.getName()), dish));

        Map<String, MatchedDish> matches = new HashMap<>();
        for (String name : normalizedNames) {
            Dish dish = byName.get(name);
            if (dish != null) {
                matches.put(name, new MatchedDish(dish, "name"));
            } else {
                aliasRepository.findByOwnerIdAndNormalizedAlias(ownerId, name)
                        .ifPresent(alias -> matches.put(name, new MatchedDish(alias.getDish(), "nickname")));
            }
        }
        return matches;
    }

    private Set<LocalDate> datesWithSales(Long dishId) {
        return saleRepository.findByDishIdOrderBySaleDateAsc(dishId).stream()
                .map(DailySale::getSaleDate)
                .collect(Collectors.toSet());
    }
}
