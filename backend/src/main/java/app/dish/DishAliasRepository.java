package app.dish;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DishAliasRepository extends JpaRepository<DishAlias, Long> {

    List<DishAlias> findByDishIdOrderByAliasAsc(Long dishId);

    Optional<DishAlias> findByOwnerIdAndNormalizedAlias(Long ownerId, String normalizedAlias);
}
