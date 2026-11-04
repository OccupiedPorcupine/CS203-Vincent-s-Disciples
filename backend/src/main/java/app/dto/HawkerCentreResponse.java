package app.dto;

import app.service.StallClosureService.HawkerCentreChoice;
import io.swagger.v3.oas.annotations.media.Schema;

public record HawkerCentreResponse(
        Long id,
        @Schema(example = "Adam Road Food Centre") String name,
        @Schema(example = "2, Adam Road, Singapore 289876") String address
) {
    public static HawkerCentreResponse from(HawkerCentreChoice centre) {
        return new HawkerCentreResponse(centre.id(), centre.name(), centre.address());
    }
}
