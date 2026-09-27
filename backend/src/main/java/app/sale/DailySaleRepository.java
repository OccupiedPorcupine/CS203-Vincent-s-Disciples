package app.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DailySaleRepository extends JpaRepository<DailySale, Long> {

    List<DailySale>
    findByDishIdAndSaleDateBeforeOrderBySaleDateAsc(
            Long dishId,
            LocalDate forecastDate
    );
    
    boolean existsByDishIdAndSaleDate(Long dishId, LocalDate saleDate);
}