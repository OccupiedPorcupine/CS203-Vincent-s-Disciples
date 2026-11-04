package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record SetHawkerCentreRequest(
        @Schema(description = "Id from GET /api/closures/hawker-centres; null if the stall isn't in a hawker centre", example = "12") Long hawkerCentreId
) {}
