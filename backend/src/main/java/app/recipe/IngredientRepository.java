package app.recipe;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IngredientRepository extends JpaRepository<Ingredient, Long> {
    List<Ingredient> findByOwnerIdOrderByNameAsc(Long ownerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Ingredient i where i.id = :id and i.owner.id = :ownerId")
    Optional<Ingredient> lockForStock(@Param("id") Long id,
                                      @Param("ownerId") Long ownerId);

    @Modifying
    @Query("""
            update Ingredient i set i.stockQuantity = null, i.stockCountedOn = null, i.stockExpiresOn = null
            where i.id = :id
            """)
    void clearStock(@Param("id") Long id);

    Optional<Ingredient> findByIdAndOwnerId(Long id, Long ownerId);
    Optional<Ingredient> findByOwnerIdAndNameKey(Long ownerId, String nameKey);
}
