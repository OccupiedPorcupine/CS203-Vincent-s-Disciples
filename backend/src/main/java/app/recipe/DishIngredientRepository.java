package app.recipe;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DishIngredientRepository extends JpaRepository<DishIngredient, Long> {
    List<DishIngredient> findByOwnerId(Long ownerId);
    List<DishIngredient> findByDishId(Long dishId);
    List<DishIngredient> findByIngredientId(Long ingredientId);
}
