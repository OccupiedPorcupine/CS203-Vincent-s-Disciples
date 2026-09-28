package app.controller;

import app.dish.Dish;
import app.dto.CreateDishRequest;
import app.dto.DishResponse;
import app.service.DishService;
import app.user.AppUser;
import app.user.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DishControllerTest {

    @Test
    void createDishReturnsOnlyIdAndName() {
        DishService dishService = mock(DishService.class);
        AppUser owner = AppUser.localUser("person@example.com", "{pbkdf2}secret-hash", "Person");
        when(dishService.createDish("Chicken rice", 7L)).thenReturn(new Dish("Chicken rice", owner));
        AuthenticatedUser user = new AuthenticatedUser(7L, null, "person@example.com", "Person", null, "ROLE_USER");

        DishResponse response = new DishController(dishService).createDish(user, new CreateDishRequest("Chicken rice"));

        assertEquals("Chicken rice", response.name());
        // Guards against returning the entity again, which exposed the owner's password hash.
        assertEquals(List.of("id", "name"),
                Arrays.stream(DishResponse.class.getRecordComponents()).map(c -> c.getName()).toList());
    }
}
