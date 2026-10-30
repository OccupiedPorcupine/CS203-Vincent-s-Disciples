package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record CloseDateRequest(
        @Size(max = 100) @Schema(description = "Why the stall is closed (optional)", example = "Centre cleaning") String reason
) {}
