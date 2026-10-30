package app.dto;

import java.util.List;

public record SalesImportResponse(
        int rowsAdded,
        int rowsOverwritten,
        int rowsSkipped,
        int rowsLeftOut,
        List<CreatedDish> createdDishes,
        TrainingDataSourceResponse source
) {
    public record CreatedDish(Long id, String name) {}
}
