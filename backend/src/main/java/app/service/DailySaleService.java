package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.AddDailySalesRequest;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DailySaleService {

    private final DishRepository dishRepository;
    private final DailySaleRepository dailySaleRepository;

    public DailySaleService(DishRepository dishRepository,DailySaleRepository dailySaleRepository) {
        this.dishRepository = dishRepository;
        this.dailySaleRepository = dailySaleRepository;
    }

    public List<DailySale> addSales(Long dishId, Long ownerId, AddDailySalesRequest request) {
        Dish dish = dishRepository
                .findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Dish not found"));

        List<DailySale> salesToSave = new ArrayList<>();

        for (AddDailySalesRequest.SaleInput input : request.sales()) {

            if (input.quantitySold() < 0) {
                throw new IllegalArgumentException("Quantity sold cannot be negative");
            }

            if (dailySaleRepository.existsByDishIdAndSaleDate(dishId, input.date())) {
                throw new IllegalArgumentException("Sales already exist for " + input.date());
            }

            salesToSave.add(new DailySale(dish, input.date(), input.quantitySold()));
        }

        return dailySaleRepository.saveAll(salesToSave);
        
    }
}