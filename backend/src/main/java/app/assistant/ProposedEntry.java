package app.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

// A checked proposal entry, shown on the confirm card. newDishName is set (and dishId null)
// when confirming would create the dish. previousQuantity is the quantity already recorded
// for that day, so a correction can be shown as "40 → 45"; null if the day has no record.
// isNewDish() is serialized as "newDish" for the browser and ignored when read back.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposedEntry(
        @Schema(example = "3") Long dishId,
        @Schema(example = "Roasted chicken rice") String dishName,
        @Schema(description = "Name of the dish to create on confirm; null for an existing dish") String newDishName,
        @Schema(example = "鸡饭") String spokenName,
        @Schema(example = "2026-10-28") LocalDate date,
        @Schema(example = "45") int quantity,
        @Schema(example = "40") Integer previousQuantity
) {
    public boolean isNewDish() {
        return dishId == null;
    }
}
