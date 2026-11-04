package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SetNeaDaysRequest(
        @NotNull @Schema(description = "First day, inclusive", format = "date") LocalDate from,
        @NotNull @Schema(description = "Last day, inclusive", format = "date") LocalDate to,
        @NotNull @Schema(description = "true: the stall opens on NEA's closed days in this range anyway; false: it's closed after all") Boolean open
) {}
