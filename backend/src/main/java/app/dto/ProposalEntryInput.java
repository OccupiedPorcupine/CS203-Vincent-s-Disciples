package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

// One day's sales of one dish, as proposed by the assistant or edited by the user before confirming.
// Exactly one of dishId and newDishName is set.
public record ProposalEntryInput(
        @Schema(description = "Existing dish ID; null for a new dish", example = "3") Long dishId,
        @Schema(description = "Name for a dish to create on confirm; null for an existing dish", example = "Laksa") String newDishName,
        @Schema(description = "The name the user used, saved as an alias on confirm", example = "鸡饭") String spokenName,
        @Schema(description = "Sales date", example = "2026-10-28") LocalDate date,
        @Schema(description = "Quantity sold", example = "42") Integer quantity
) {}
