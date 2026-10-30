package app.controller;

import app.dish.Dish;
import app.dto.CreateDishRequest;
import app.dto.DishResponse;
import app.price.DishPriceService;
import app.price.PriceBook;
import app.service.DishService;
import app.user.AppUser;
import app.user.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DishControllerTest {

    private static DishController controller(DishService dishService) {
        DishPriceService priceService = mock(DishPriceService.class);
        when(priceService.priceBook(7L)).thenReturn(new PriceBook(List.of()));
        return new DishController(dishService, priceService, Clock.systemUTC());
    }

    @Test
    void createDishReturnsOnlyIdNameAndPrices() {
        DishService dishService = mock(DishService.class);
        AppUser owner = AppUser.localUser("person@example.com", "{pbkdf2}secret-hash", "Person");
        when(dishService.createDish("Chicken rice", 7L)).thenReturn(new Dish("Chicken rice", owner));
        AuthenticatedUser user = new AuthenticatedUser(7L, null, "person@example.com", "Person", null, "ROLE_USER");

        DishResponse response = controller(dishService).createDish(user, new CreateDishRequest("Chicken rice"));

        assertEquals("Chicken rice", response.name());
        // Guards against returning the entity again, which exposed the owner's password hash.
        assertEquals(List.of("id", "name", "price", "unitCost"),
                Arrays.stream(DishResponse.class.getRecordComponents()).map(c -> c.getName()).toList());
    }

    @Test
    void getDishesListsOnlyTheSignedInUsersDishes() {
        DishService dishService = mock(DishService.class);
        AppUser owner = AppUser.localUser("person@example.com", "{pbkdf2}secret-hash", "Person");
        when(dishService.getDishes(7L)).thenReturn(List.of(new Dish("Chicken rice", owner), new Dish("Laksa", owner)));
        AuthenticatedUser user = new AuthenticatedUser(7L, null, "person@example.com", "Person", null, "ROLE_USER");

        List<DishResponse> response = controller(dishService).getDishes(user);

        assertEquals(List.of("Chicken rice", "Laksa"), response.stream().map(DishResponse::name).toList());
    }
}
