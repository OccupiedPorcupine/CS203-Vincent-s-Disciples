package app.service;

import app.dish.Dish;
import app.dish.DishRepository;
import app.dto.AddDailySalesRequest;
import app.dto.UpdateDailySaleResponse;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void addSalesRejectsTheSameDateTwiceInOneBatch() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(mock(Dish.class)));
        var day = LocalDate.of(2026, 10, 28);
        var request = new AddDailySalesRequest(List.of(
                new AddDailySalesRequest.SaleInput(day, 40),
                new AddDailySalesRequest.SaleInput(day, 45)));

        var service = new DailySaleService(dishRepository, saleRepository);

        var error = assertThrows(IllegalArgumentException.class, () -> service.addSales(3L, 7L, request));
        assertEquals("2026-10-28 appears more than once", error.getMessage());
        verify(saleRepository, never()).saveAll(any());
    }

    @Test
    void setSaleCreatesARecordForANewDay() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        var day = LocalDate.of(2026, 10, 28);
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(mock(Dish.class)));
        when(saleRepository.findByDishIdAndSaleDate(3L, day)).thenReturn(Optional.empty());

        var response = new DailySaleService(dishRepository, saleRepository).setSale(3L, 7L, day, 42);

        assertEquals(new UpdateDailySaleResponse(day, 42, null), response);
        var saved = ArgumentCaptor.forClass(DailySale.class);
        verify(saleRepository).save(saved.capture());
        assertEquals(day, saved.getValue().getSaleDate());
        assertEquals(42, saved.getValue().getQuantitySold());
    }

    @Test
    void setSaleReplacesAnExistingQuantityAndReportsThePreviousOne() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        var dish = mock(Dish.class);
        var day = LocalDate.of(2026, 10, 28);
        var existing = new DailySale(dish, day, 40);
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(dish));
        when(saleRepository.findByDishIdAndSaleDate(3L, day)).thenReturn(Optional.of(existing));

        var response = new DailySaleService(dishRepository, saleRepository).setSale(3L, 7L, day, 45);

        assertEquals(new UpdateDailySaleResponse(day, 45, 40), response);
        assertEquals(45, existing.getQuantitySold());
        verify(saleRepository).save(existing);
    }

    @Test
    void setSaleRejectsAnotherUsersDish() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        when(dishRepository.findByIdAndOwnerId(3L, 8L)).thenReturn(Optional.empty());

        var service = new DailySaleService(dishRepository, saleRepository);

        assertThrows(IllegalArgumentException.class, () -> service.setSale(3L, 8L, LocalDate.of(2026, 10, 28), 45));
        verify(saleRepository, never()).save(any());
    }

    @Test
    void setSaleRejectsANegativeQuantity() {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        when(dishRepository.findByIdAndOwnerId(3L, 7L)).thenReturn(Optional.of(mock(Dish.class)));

        var service = new DailySaleService(dishRepository, saleRepository);

        assertThrows(IllegalArgumentException.class, () -> service.setSale(3L, 7L, LocalDate.of(2026, 10, 28), -1));
        verify(saleRepository, never()).save(any());
    }
}
