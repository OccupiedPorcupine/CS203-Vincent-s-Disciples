package app.assistant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

// A checked change to the stall's closed days, shown on the confirm card next to any sales:
// closed = true marks the day as closed, false opens it again.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProposedClosure(
        @Schema(example = "2026-11-05") LocalDate date,
        @Schema(description = "True to mark the day as closed, false to open it again", example = "true") boolean closed,
        @Schema(description = "Why the stall is closed, if the user said", example = "Centre cleaning") String reason
) {}
