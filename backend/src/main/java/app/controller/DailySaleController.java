package app.controller;

import app.dto.AddDailySalesRequest;
import app.dto.AddDailySalesResponse;
import app.service.DailySaleService;
import app.user.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.validation.Valid;

@Tag(name = "Sales", description = "Daily sales data operations")
@RestController
@RequestMapping("/api/dishes/{dishId}/sales")
public class DailySaleController {

    private final DailySaleService dailySaleService;

    public DailySaleController(DailySaleService dailySaleService) {
        this.dailySaleService = dailySaleService;
    }

    @Operation(summary = "Add daily sales", description = "Adds daily sales records for a dish belonging to the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sales records added successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid sales data", content = @Content),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "Dish not found", content = @Content)
    })

    @PostMapping
    public AddDailySalesResponse addSales(
            @PathVariable Long dishId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Daily sales history for the dish. For forecasting, provide at least 28 consecutive days of sales immediately preceding the forecast date.",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "sales": [
                                                { "date": "2026-09-01", "quantitySold": 39 },
                                                { "date": "2026-09-02", "quantitySold": 42 },
                                                { "date": "2026-09-03", "quantitySold": 36 },
                                                { "date": "2026-09-04", "quantitySold": 45 },
                                                { "date": "2026-09-05", "quantitySold": 53 },
                                                { "date": "2026-09-06", "quantitySold": 57 },
                                                { "date": "2026-09-07", "quantitySold": 40 },
                                                { "date": "2026-09-08", "quantitySold": 41 },
                                                { "date": "2026-09-09", "quantitySold": 44 },
                                                { "date": "2026-09-10", "quantitySold": 38 },
                                                { "date": "2026-09-11", "quantitySold": 47 },
                                                { "date": "2026-09-12", "quantitySold": 54 },
                                                { "date": "2026-09-13", "quantitySold": 56 },
                                                { "date": "2026-09-14", "quantitySold": 39 },
                                                { "date": "2026-09-15", "quantitySold": 38 },
                                                { "date": "2026-09-16", "quantitySold": 41 },
                                                { "date": "2026-09-17", "quantitySold": 35 },
                                                { "date": "2026-09-18", "quantitySold": 44 },
                                                { "date": "2026-09-19", "quantitySold": 51 },
                                                { "date": "2026-09-20", "quantitySold": 55 },
                                                { "date": "2026-09-21", "quantitySold": 39 },
                                                { "date": "2026-09-22", "quantitySold": 40 },
                                                { "date": "2026-09-23", "quantitySold": 43 },
                                                { "date": "2026-09-24", "quantitySold": 37 },
                                                { "date": "2026-09-25", "quantitySold": 46 },
                                                { "date": "2026-09-26", "quantitySold": 52 },
                                                { "date": "2026-09-27", "quantitySold": 42 },
                                                { "date": "2026-09-28", "quantitySold": 45 }
                                              ]
                                            }
                                            """
                            )
                    )
            )
            @RequestBody AddDailySalesRequest request
    ) {
        int added = dailySaleService.addSales(dishId, user.id(), request);
        return new AddDailySalesResponse(added);
    }

}