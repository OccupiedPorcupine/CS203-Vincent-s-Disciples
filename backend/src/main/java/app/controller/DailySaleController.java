package app.controller;

import app.dto.AddDailySalesRequest;
import app.dto.AddDailySalesResponse;
import app.service.DailySaleService;
import app.user.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dishes/{dishId}/sales")
public class DailySaleController {

    private final DailySaleService dailySaleService;

    public DailySaleController(DailySaleService dailySaleService) {
        this.dailySaleService = dailySaleService;
    }

    @PostMapping
    public AddDailySalesResponse addSales(@PathVariable Long dishId, @AuthenticationPrincipal AuthenticatedUser user, @RequestBody AddDailySalesRequest request) {
        int added = dailySaleService.addSales(dishId, user.id(), request);
        return new AddDailySalesResponse(added);
    }
}