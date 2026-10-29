package app.service;

import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.exception.AliasInUseException;
import app.user.AppUser;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DishAliasServiceTest {

    private final AppUser owner = AppUser.localUser("person@example.com", "{pbkdf2}secret-hash", "Person");

    private Dish dish(long id, String name) {
        var dish = mock(Dish.class);
        when(dish.getId()).thenReturn(id);
        when(dish.getName()).thenReturn(name);
        when(dish.getOwner()).thenReturn(owner);
        return dish;
    }

    @Test
    void normalizeIgnoresCaseSpacingAndFullWidthCharacters() {
        assertEquals("chicken rice", DishAlias.normalize("  Chicken   RICE "));
        assertEquals("chicken rice", DishAlias.normalize("Ｃｈｉｃｋｅｎ　Ｒｉｃｅ"));
        assertEquals("鸡饭", DishAlias.normalize(" 鸡饭 "));
    }

    @Test
    void addAliasSavesANewName() {
        var dishRepository = mock(DishRepository.class);
        var aliasRepository = mock(DishAliasRepository.class);
        var chickenRice = dish(3L, "Chicken rice");
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(chickenRice));
        when(aliasRepository.findByOwnerIdAndNormalizedAlias(7L, "鸡饭")).thenReturn(Optional.empty());
        when(aliasRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var alias = new DishAliasService(dishRepository, aliasRepository).addAlias(3L, 7L, " 鸡饭 ");

        assertEquals("鸡饭", alias.getAlias());
        assertSame(chickenRice, alias.getDish());
    }

    @Test
    void addAliasReturnsTheExistingAliasWhenTheDishAlreadyHasThatName() {
        var dishRepository = mock(DishRepository.class);
        var aliasRepository = mock(DishAliasRepository.class);
        var chickenRice = dish(3L, "Chicken rice");
        var existing = new DishAlias(chickenRice, "Roast Chicken");
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(chickenRice));
        when(aliasRepository.findByOwnerIdAndNormalizedAlias(7L, "roast chicken")).thenReturn(Optional.of(existing));

        var alias = new DishAliasService(dishRepository, aliasRepository).addAlias(3L, 7L, "ROAST  chicken");

        assertSame(existing, alias);
        verify(aliasRepository, never()).save(any());
    }

    @Test
    void addAliasRejectsANameUsedForAnotherDish() {
        var dishRepository = mock(DishRepository.class);
        var aliasRepository = mock(DishAliasRepository.class);
        var chickenRice = dish(3L, "Chicken rice");
        var charSiew = dish(4L, "Char siew rice");
        var existing = new DishAlias(chickenRice, "The usual");
        when(dishRepository.findByIdAndOwnerId(4L, 7L)).thenReturn(Optional.of(charSiew));
        when(aliasRepository.findByOwnerIdAndNormalizedAlias(7L, "the usual")).thenReturn(Optional.of(existing));

        var service = new DishAliasService(dishRepository, aliasRepository);

        var error = assertThrows(AliasInUseException.class, () -> service.addAlias(4L, 7L, "the usual"));
        assertEquals("\"the usual\" is already a name for Chicken rice", error.getMessage());
        verify(aliasRepository, never()).save(any());
    }

    @Test
    void addAliasRejectsAnotherUsersDish() {
        var dishRepository = mock(DishRepository.class);
        var aliasRepository = mock(DishAliasRepository.class);
        when(dishRepository.findByIdAndOwnerId(3L, 8L)).thenReturn(Optional.empty());

        var service = new DishAliasService(dishRepository, aliasRepository);

        assertThrows(IllegalArgumentException.class, () -> service.addAlias(3L, 8L, "鸡饭"));
        verify(aliasRepository, never()).findByOwnerIdAndNormalizedAlias(any(), anyString());
        verify(aliasRepository, never()).save(any());
    }
}
