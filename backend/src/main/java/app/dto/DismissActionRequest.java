package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DismissActionRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z-]+(:[A-Za-z0-9-]+)?")
        @Schema(description = "The card's key from GET /api/actions", example = "missing-sales:2026-11-03") String key
) {}
