package app.controller;

import app.dto.AddDailySalesRequest;
import app.sale.DailySale;
import app.service.DailySaleService;
import app.user.AuthenticatedUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dishes/{dishId}/sales")
public class DailySaleController {

    private final DailySaleService dailySaleService;

    public DailySaleController(DailySaleService dailySaleService) {
        this.dailySaleService = dailySaleService;
    }

    @PostMapping
    public List<DailySale> addSales(
            @PathVariable Long dishId,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody AddDailySalesRequest request
    ) {
        return dailySaleService.addSales(
                dishId,
                user.id(),
                request
        );
    }
}