package app.dish;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DishRepository extends JpaRepository<Dish, Long> {

    List<Dish> findByOwnerId(Long ownerId);

    Optional<Dish> findByIdAndOwnerId(
            Long id,
            Long ownerId
    );
}