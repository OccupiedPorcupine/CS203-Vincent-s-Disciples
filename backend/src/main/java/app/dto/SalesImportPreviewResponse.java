package app.dto;

import app.salesimport.SalesFileReader.RowError;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

// What importing an uploaded file would do. Nothing is written until POST .../import.
public record SalesImportPreviewResponse(
        Long sourceId,
        String filename,
        @Schema(description = "Workbook sheet the rows come from; null for CSV", example = "Daily Item Sales") String sheet,
        @Schema(description = "Valid rows (one dish on one day)") int rows,
        LocalDate firstDate,
        LocalDate lastDate,
        List<DishInFile> dishes,
        @Schema(description = "Rows for matched dishes on dates that already have sales; these are skipped or overwritten")
        int rowsWithExistingSales,
        @Schema(description = "Rows added to another row because the same dish and date appear more than once")
        int rowsCombined,
        @Schema(description = "Rows with problems; the file can't be imported until they are fixed") int errorCount,
        @Schema(description = "The first 50 problems") List<RowError> errors,
        TrainingDataSourceResponse.LastImport lastImport
) {
    public record DishInFile(
            @Schema(description = "Name as written in the file", example = "Roasted Chicken Rice") String name,
            int rows,
            long portions,
            @Schema(description = "The dish it matches by name or nickname; null if it matches none") Match match,
            int rowsWithExistingSales
    ) {}

    public record Match(Long dishId, String dishName, @Schema(example = "name", allowableValues = {"name", "nickname"}) String by) {}
}
