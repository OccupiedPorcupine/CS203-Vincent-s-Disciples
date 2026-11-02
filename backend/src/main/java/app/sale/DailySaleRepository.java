package app.sale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailySaleRepository extends JpaRepository<DailySale, Long> {

    List<DailySale>
    findByDishIdAndSaleDateBeforeOrderBySaleDateAsc(
            Long dishId,
            LocalDate forecastDate
    );
    
    List<DailySale> findByDishIdOrderBySaleDateAsc(Long dishId);

    List<DailySale> findByDishOwnerIdAndSaleDateBetween(Long ownerId, LocalDate from, LocalDate to);

    Optional<DailySale> findFirstByDishOwnerIdOrderBySaleDateAsc(Long ownerId);

    boolean existsByDishIdAndSaleDate(Long dishId, LocalDate saleDate);

    Optional<DailySale> findByDishIdAndSaleDate(Long dishId, LocalDate saleDate);
}