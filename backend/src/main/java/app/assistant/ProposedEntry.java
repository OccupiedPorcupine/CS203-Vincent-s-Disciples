package app.assistant;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalTime;

// A checked proposal entry, shown on the confirm card. newDishName is set (and dishId null)
// when confirming would create the dish. previousQuantity is the quantity already recorded
// for that day, so a correction can be shown as "40 → 45"; null if the day has no record.
// soldOutAt and leftoverQuantity are what will be saved for the day (null clears them).
// isNewDish() is serialized as "newDish" for the browser and ignored when read back.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposedEntry(
        @Schema(example = "3") Long dishId,
        @Schema(example = "Roasted chicken rice") String dishName,
        @Schema(description = "Name of the dish to create on confirm; null for an existing dish") String newDishName,
        @Schema(example = "鸡饭") String spokenName,
        @Schema(example = "2026-10-28") LocalDate date,
        @Schema(example = "45") int quantity,
        @Schema(example = "40") Integer previousQuantity,
        @JsonFormat(pattern = "HH:mm") @Schema(type = "string", description = "When the dish sold out; null if it didn't", example = "12:40") LocalTime soldOutAt,
        @Schema(description = "Portions left over; null if not recorded", example = "3") Integer leftoverQuantity
) {
    public ProposedEntry(Long dishId, String dishName, String newDishName, String spokenName, LocalDate date,
                         int quantity, Integer previousQuantity) {
        this(dishId, dishName, newDishName, spokenName, date, quantity, previousQuantity, null, null);
    }

    public boolean isNewDish() {
        return dishId == null;
    }
}
