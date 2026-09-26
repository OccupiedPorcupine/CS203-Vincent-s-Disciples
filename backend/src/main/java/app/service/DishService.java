package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.user.AppUser;
import app.user.AppUserRepository;
import org.springframework.stereotype.Service;

@Service
public class DishService {

    private final DishRepository dishRepository;
    private final AppUserRepository appUserRepository;

    public DishService(DishRepository dishRepository, AppUserRepository appUserRepository) {
        this.dishRepository = dishRepository;
        this.appUserRepository = appUserRepository;
    }

    public Dish createDish(String name,Long ownerId) {
        AppUser owner = appUserRepository
                .findById(ownerId)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "User not found"
                        )
                );

        Dish dish = new Dish(name, owner);

        return dishRepository.save(dish);
    }
}