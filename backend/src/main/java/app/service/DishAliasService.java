package app.service;

import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.exception.AliasInUseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class DishAliasService {

    private static final int MAX_ALIAS_LENGTH = 100;

    private final DishRepository dishRepository;
    private final DishAliasRepository dishAliasRepository;

    public DishAliasService(DishRepository dishRepository, DishAliasRepository dishAliasRepository) {
        this.dishRepository = dishRepository;
        this.dishAliasRepository = dishAliasRepository;
    }

    public List<DishAlias> getAliases(Long dishId, Long ownerId) {
        findOwnedDish(dishId, ownerId);
        return dishAliasRepository.findByDishIdOrderByAliasAsc(dishId);
    }

    // Adding a name the dish already has returns the existing alias, so callers such as the
    // voice assistant can save every name they see without checking first.
    @Transactional
    public DishAlias addAlias(Long dishId, Long ownerId, String alias) {
        Dish dish = findOwnedDish(dishId, ownerId);
        String normalized = DishAlias.normalize(alias);

        if (normalized.isEmpty() || normalized.length() > MAX_ALIAS_LENGTH) {
            throw new IllegalArgumentException("Alias must be 1 to " + MAX_ALIAS_LENGTH + " characters");
        }

        Optional<DishAlias> existing = dishAliasRepository.findByOwnerIdAndNormalizedAlias(ownerId, normalized);
        if (existing.isPresent()) {
            if (!existing.get().getDish().getId().equals(dishId)) {
                throw new AliasInUseException(alias.strip(), existing.get().getDish().getName());
            }
            return existing.get();
        }

        return dishAliasRepository.save(new DishAlias(dish, alias));
    }

    private Dish findOwnedDish(Long dishId, Long ownerId) {
        return dishRepository
                .findByIdAndOwnerId(dishId, ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException("Dish not found"));
    }
}
