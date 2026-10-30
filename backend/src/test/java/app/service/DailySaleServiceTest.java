package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class DailySaleServiceTest {

    @Test
    void getSalesReturnsTheOwnedDishsSales() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        var dish = mock(Dish.class);
        var sale = new DailySale(dish, LocalDate.of(2026, 9, 27), 42);
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(dish));
        when(saleRepository.findByDishIdOrderBySaleDateAsc(3L)).thenReturn(List.of(sale));

        var sales = new DailySaleService(dishRepository, saleRepository).getSales(3L, 7L);

        assertEquals(List.of(sale), sales);
    }

    @Test
    void getSalesRejectsAnotherUsersDish() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        when(dishRepository.findByIdAndOwnerId(3L, 8L)).thenReturn(Optional.empty());

        var service = new DailySaleService(dishRepository, saleRepository);

        var error = assertThrows(IllegalArgumentException.class, () -> service.getSales(3L, 8L));
        assertEquals("Dish not found", error.getMessage());
        verify(saleRepository, never()).findByDishIdOrderBySaleDateAsc(anyLong());
    }
}
