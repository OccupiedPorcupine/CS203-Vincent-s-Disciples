package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.MlPredictionRequest;
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

    public List<MlPredictionRequest.SalesRecord> getSalesHistory(Long dishId, LocalDate forecastDate) {
        List<DailySale> sales =
                dailySaleRepository
                        .findByDishIdAndSaleDateBeforeOrderBySaleDateAsc(
                                dishId,
                                forecastDate
                        );

        return sales.stream()
                .map(
                        sale ->
                                new MlPredictionRequest.SalesRecord(
                                        sale.getSaleDate().toString(),
                                        sale.getQuantitySold()
                                )
                )
                .toList();
    }
}