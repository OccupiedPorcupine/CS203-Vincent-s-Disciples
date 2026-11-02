package app.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

// An owner's dish prices over time. The price on a date is the latest one that took effect on or
// before it; dates before a dish's first price use that first price, so history recorded before
// prices were set still has a value.
public final class PriceBook {

    public record Price(LocalDate effectiveFrom, BigDecimal price, BigDecimal unitCost) {}

    private final Map<Long, List<Price>> byDish = new HashMap<>();

    public PriceBook(List<DishPrice> rows) {
        for (DishPrice row : rows) {
            byDish.computeIfAbsent(row.getDish().getId(), ignored -> new ArrayList<>())
                    .add(new Price(row.getEffectiveFrom(), row.getPrice(), row.getUnitCost()));
        }
        byDish.values().forEach(prices -> prices.sort(Comparator.comparing(Price::effectiveFrom)));
    }

    public Optional<Price> on(Long dishId, LocalDate date) {
        List<Price> prices = byDish.get(dishId);
        if (prices == null) return Optional.empty();
        Price applying = prices.getFirst();
        for (Price price : prices) {
            if (price.effectiveFrom().isAfter(date)) break;
            applying = price;
        }
        return Optional.of(applying);
    }

    public boolean hasPrice(Long dishId) {
        return byDish.containsKey(dishId);
    }
}
