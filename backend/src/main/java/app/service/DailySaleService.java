package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.AddDailySalesRequest;
import app.dto.UpdateDailySaleResponse;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class DailySaleService {

    public static final int MAX_LEFTOVER = 9999;

    private final DishRepository dishRepository;
    private final DailySaleRepository dailySaleRepository;

    public DailySaleService(DishRepository dishRepository,DailySaleRepository dailySaleRepository) {
        this.dishRepository = dishRepository;
        this.dailySaleRepository = dailySaleRepository;
    }

    public int addSales(Long dishId, Long ownerId, AddDailySalesRequest request) {
        Dish dish = dishRepository
                .findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Dish not found"));

        List<DailySale> salesToSave = new ArrayList<>();
        Set<LocalDate> datesInRequest = new HashSet<>();

        for (AddDailySalesRequest.SaleInput input : request.sales()) {

            if (input.quantitySold() < 0) {
                throw new IllegalArgumentException("Quantity sold cannot be negative");
            }

            if (!datesInRequest.add(input.date())) {
                throw new IllegalArgumentException(input.date() + " appears more than once");
            }

            if (dailySaleRepository.existsByDishIdAndSaleDate(dishId, input.date())) {
                throw new IllegalArgumentException("Sales already exist for " + input.date());
            }
            outcomeProblem(input.soldOutAt(), input.leftoverQuantity()).ifPresent(problem -> {
                throw new IllegalArgumentException(input.date() + ": " + problem);
            });

            DailySale sale = new DailySale(dish, input.date(), input.quantitySold());
            sale.setOutcome(input.soldOutAt(), input.leftoverQuantity());
            salesToSave.add(sale);
        }

        dailySaleRepository.saveAll(salesToSave);
        return salesToSave.size();

    }

    public List<DailySale> getSales(Long dishId, Long ownerId) {
        dishRepository
                .findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Dish not found"));

        return dailySaleRepository.findByDishIdOrderBySaleDateAsc(dishId);
    }

    // Records one day's quantity, replacing any quantity already recorded for that day. A sold-out
    // time or leftovers already recorded are kept.
    @Transactional
    public UpdateDailySaleResponse setSale(Long dishId, Long ownerId, LocalDate date, int quantitySold) {
        Optional<DailySale> existing = dailySaleRepository.findByDishIdAndSaleDate(dishId, date);
        return setSale(dishId, ownerId, date, quantitySold,
                existing.map(DailySale::getSoldOutAt).orElse(null),
                existing.map(DailySale::getLeftoverQuantity).orElse(null));
    }

    // Records one day's sales, replacing everything already recorded for that day: null clears
    // the sold-out time or leftovers.
    @Transactional
    public UpdateDailySaleResponse setSale(Long dishId, Long ownerId, LocalDate date, int quantitySold,
                                           LocalTime soldOutAt, Integer leftoverQuantity) {
        Dish dish = dishRepository
                .findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Dish not found"));

        if (quantitySold < 0) {
            throw new IllegalArgumentException("Quantity sold cannot be negative");
        }
        outcomeProblem(soldOutAt, leftoverQuantity).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });

        Optional<DailySale> existing = dailySaleRepository.findByDishIdAndSaleDate(dishId, date);
        Integer previousQuantity = existing.map(DailySale::getQuantitySold).orElse(null);

        DailySale sale = existing.orElseGet(() -> new DailySale(dish, date, quantitySold));
        sale.setQuantitySold(quantitySold);
        sale.setOutcome(soldOutAt, leftoverQuantity);
        dailySaleRepository.save(sale);

        return new UpdateDailySaleResponse(date, quantitySold, previousQuantity, soldOutAt, leftoverQuantity);
    }

    // What's wrong with a day's sold-out time and leftovers, if anything.
    public static Optional<String> outcomeProblem(LocalTime soldOutAt, Integer leftoverQuantity) {
        if (leftoverQuantity != null && (leftoverQuantity < 0 || leftoverQuantity > MAX_LEFTOVER)) {
            return Optional.of("Leftovers must be between 0 and " + MAX_LEFTOVER + ".");
        }
        if (soldOutAt != null && leftoverQuantity != null && leftoverQuantity > 0) {
            return Optional.of("A dish that sold out has no leftovers.");
        }
        return Optional.empty();
    }

    public record ImportResult(int added, int overwritten, int skipped) {}

    // Writes one dish's imported sales. A date that already has sales is replaced when overwrite
    // is true, otherwise kept; either way a date whose quantity is already the same is counted
    // as skipped. Run inside the import's transaction, so a failure saves nothing.
    @Transactional
    public ImportResult importSales(Dish dish, Map<LocalDate, Integer> quantities, boolean overwrite) {
        Map<LocalDate, DailySale> existing = new HashMap<>();
        dailySaleRepository.findByDishIdOrderBySaleDateAsc(dish.getId())
                .forEach(sale -> existing.put(sale.getSaleDate(), sale));

        List<DailySale> salesToSave = new ArrayList<>();
        int added = 0, overwritten = 0, skipped = 0;
        for (Map.Entry<LocalDate, Integer> entry : quantities.entrySet()) {
            int quantity = entry.getValue();
            if (quantity < 0) {
                throw new IllegalArgumentException("Quantity sold cannot be negative");
            }

            DailySale sale = existing.get(entry.getKey());
            if (sale == null) {
                salesToSave.add(new DailySale(dish, entry.getKey(), quantity));
                added++;
            } else if (overwrite && sale.getQuantitySold() != quantity) {
                sale.setQuantitySold(quantity);
                salesToSave.add(sale);
                overwritten++;
            } else {
                skipped++;
            }
        }

        dailySaleRepository.saveAll(salesToSave);
        return new ImportResult(added, overwritten, skipped);
    }
}
