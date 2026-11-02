package app.dto;

import java.util.List;

public record SalesImportResponse(
        int rowsAdded,
        int rowsOverwritten,
        int rowsSkipped,
        int rowsLeftOut,
        List<CreatedDish> createdDishes,
        int pricesSet,
        TrainingDataSourceResponse source
) {
    public record CreatedDish(Long id, String name) {}
}
