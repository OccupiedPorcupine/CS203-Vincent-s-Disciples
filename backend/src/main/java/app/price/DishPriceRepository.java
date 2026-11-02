package app.price;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DishPriceRepository extends JpaRepository<DishPrice, Long> {
    List<DishPrice> findByDishIdOrderByEffectiveFromAsc(Long dishId);
    List<DishPrice> findByOwnerIdOrderByEffectiveFromAsc(Long ownerId);
    Optional<DishPrice> findByDishIdAndEffectiveFrom(Long dishId, LocalDate effectiveFrom);
}
