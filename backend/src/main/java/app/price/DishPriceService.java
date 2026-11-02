package app.price;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.DishPriceResponse;
import app.dto.SetDishPriceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class DishPriceService {

    private final DishPriceRepository prices;
    private final DishRepository dishRepository;
    private final Clock clock;

    public DishPriceService(DishPriceRepository prices, DishRepository dishRepository, Clock clock) {
        this.prices = prices;
        this.dishRepository = dishRepository;
        this.clock = clock;
    }

    // Sets the price from a date onward (today by default). Setting it again for the same date
    // replaces that entry; a different date adds one, so earlier sales keep their price.
    @Transactional
    public DishPriceResponse setPrice(Long ownerId, Long dishId, SetDishPriceRequest request) {
        Dish dish = dishRepository.findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Dish not found"));
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveFrom = request.effectiveFrom() == null ? today : request.effectiveFrom();
        if (effectiveFrom.isAfter(today.plusYears(1))) {
            throw new IllegalArgumentException("A price can start at most a year from today.");
        }
        return toResponse(save(dish, effectiveFrom, request.price(), request.unitCost()));
    }

    @Transactional(readOnly = true)
    public List<DishPriceResponse> history(Long ownerId, Long dishId) {
        dishRepository.findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(() -> new IllegalArgumentException("Dish not found"));
        return prices.findByDishIdOrderByEffectiveFromAsc(dishId).stream().map(DishPriceService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PriceBook priceBook(Long ownerId) {
        return new PriceBook(prices.findByOwnerIdOrderByEffectiveFromAsc(ownerId));
    }

    // For imports: a price from a file is only used when the dish has none yet, so an import never
    // changes a price the owner set.
    @Transactional
    public boolean setPriceIfMissing(Dish dish, LocalDate effectiveFrom, BigDecimal price, BigDecimal unitCost) {
        if (!prices.findByDishIdOrderByEffectiveFromAsc(dish.getId()).isEmpty()) {
            return false;
        }
        save(dish, effectiveFrom, price, unitCost);
        return true;
    }

    private DishPrice save(Dish dish, LocalDate effectiveFrom, BigDecimal price, BigDecimal unitCost) {
        return prices.findByDishIdAndEffectiveFrom(dish.getId(), effectiveFrom)
                .map(existing -> {
                    existing.change(price, unitCost);
                    return existing;
                })
                .orElseGet(() -> prices.save(new DishPrice(dish, effectiveFrom, price, unitCost, clock.instant())));
    }

    private static DishPriceResponse toResponse(DishPrice price) {
        return new DishPriceResponse(price.getDish().getId(), price.getEffectiveFrom(), price.getPrice(), price.getUnitCost());
    }
}
