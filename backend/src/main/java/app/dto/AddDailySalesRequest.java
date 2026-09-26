package app.dto;

import java.time.LocalDate;
import java.util.List;

public record AddDailySalesRequest(List<SaleInput> sales) {

    public record SaleInput(LocalDate date, int quantitySold) {

    }
    
}