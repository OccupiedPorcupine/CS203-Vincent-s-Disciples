package app.recipe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {
    List<Ingredient> findByOwnerIdOrderByNameAsc(Long ownerId);
    Optional<Ingredient> findByOwnerIdAndNameKey(Long ownerId, String nameKey);
}
