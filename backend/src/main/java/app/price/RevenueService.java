package app.price;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.RevenueResponse;
import app.dto.RevenueResponse.DishRevenue;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

// Revenue and gross profit from recorded sales, each day valued at the price that applied then.
@Service
public class RevenueService {

    static final int MAX_DAYS = 366;

    private final DishRepository dishRepository;
    private final DailySaleRepository saleRepository;
    private final DishPriceService priceService;

    public RevenueService(DishRepository dishRepository, DailySaleRepository saleRepository, DishPriceService priceService) {
        this.dishRepository = dishRepository;
        this.saleRepository = saleRepository;
        this.priceService = priceService;
    }

    @Transactional(readOnly = true)
    public RevenueResponse summary(Long ownerId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new IllegalArgumentException("The end date is before the start date.");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new IllegalArgumentException("Choose at most a year.");

        PriceBook book = priceService.priceBook(ownerId);
        Map<Long, List<DailySale>> salesByDish = new HashMap<>();
        saleRepository.findByDishOwnerIdAndSaleDateBetween(ownerId, from, to)
                .forEach(sale -> salesByDish.computeIfAbsent(sale.getDish().getId(), ignored -> new ArrayList<>()).add(sale));

        int portions = 0, unpricedPortions = 0, portionsWithoutCost = 0;
        BigDecimal revenue = null, grossProfit = null;
        List<DishRevenue> dishes = new ArrayList<>();

        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            List<DailySale> sales = salesByDish.getOrDefault(dish.getId(), List.of());
            int dishPortions = 0, dishPortionsWithoutCost = 0;
            BigDecimal dishRevenue = null, dishCost = BigDecimal.ZERO;
            for (DailySale sale : sales) {
                int quantity = sale.getQuantitySold();
                dishPortions += quantity;
                Optional<PriceBook.Price> price = book.on(dish.getId(), sale.getSaleDate());
                if (price.isEmpty()) continue;
                BigDecimal portionsSold = BigDecimal.valueOf(quantity);
                dishRevenue = (dishRevenue == null ? BigDecimal.ZERO : dishRevenue).add(price.get().price().multiply(portionsSold));
                if (price.get().unitCost() == null) dishPortionsWithoutCost += quantity;
                else dishCost = dishCost.add(price.get().unitCost().multiply(portionsSold));
            }

            portions += dishPortions;
            if (!book.hasPrice(dish.getId())) {
                unpricedPortions += dishPortions;
            } else if (dishRevenue != null) {
                revenue = (revenue == null ? BigDecimal.ZERO : revenue).add(dishRevenue);
                portionsWithoutCost += dishPortionsWithoutCost;
            }

            BigDecimal dishProfit = dishRevenue != null && dishPortionsWithoutCost == 0 ? dishRevenue.subtract(dishCost) : null;
            if (dishProfit != null) grossProfit = (grossProfit == null ? BigDecimal.ZERO : grossProfit).add(dishProfit);

            PriceBook.Price current = book.on(dish.getId(), to).orElse(null);
            BigDecimal margin = current == null || current.unitCost() == null ? null : current.price().subtract(current.unitCost());
            dishes.add(new DishRevenue(dish.getId(), dish.getName(), dishPortions,
                    current == null ? null : current.price(), current == null ? null : current.unitCost(),
                    margin, dishRevenue, dishProfit));
        }

        dishes.sort(Comparator
                .comparing((DishRevenue dish) -> dish.grossProfit() == null ? 1 : 0)
                .thenComparing(dish -> dish.revenue() == null ? 1 : 0)
                .thenComparing(DishRevenue::grossProfit, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DishRevenue::revenue, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DishRevenue::name));
        return new RevenueResponse(from, to, portions, revenue, grossProfit, unpricedPortions, portionsWithoutCost, dishes);
    }
}
