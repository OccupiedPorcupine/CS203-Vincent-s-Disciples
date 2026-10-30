package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class ForecastDataService {

    private final DishRepository dishRepository;
    private final DailySaleRepository dailySaleRepository;

    public ForecastDataService(DishRepository dishRepository, DailySaleRepository dailySaleRepository) {
        this.dishRepository = dishRepository;
        this.dailySaleRepository = dailySaleRepository;
    }

    public Dish getOwnedDish(Long dishId, Long ownerId) {
        return dishRepository
                .findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "Dish not found"
                        )
                );
    }

    // The dish's sales strictly before the forecast date, oldest first.
    public List<DailySale> getSalesHistory(Long dishId, LocalDate forecastDate) {
        return dailySaleRepository.findByDishIdAndSaleDateBeforeOrderBySaleDateAsc(dishId, forecastDate);
    }
}