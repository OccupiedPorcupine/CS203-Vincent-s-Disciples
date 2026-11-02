package app.controller;

import app.dto.DailyRevenueResponse;
import app.dto.DishPriceResponse;
import app.dto.RevenueResponse;
import app.dto.SetDishPriceRequest;
import app.price.DishPriceService;
import app.price.RevenueService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Prices and revenue", description = "Dish prices and costs, and the revenue and margin they give")
@RestController
@RequestMapping("/api")
public class DishPriceController {

    private final DishPriceService priceService;
    private final RevenueService revenueService;

    public DishPriceController(DishPriceService priceService, RevenueService revenueService) {
        this.priceService = priceService;
        this.revenueService = revenueService;
    }

    @Operation(summary = "Set a dish's price", description = "Sets the price and optional unit cost from a date onward "
            + "(today by default). Sales before that date keep the price that applied then; a dish's first price "
            + "also values sales recorded before it.")
    @PutMapping("/dishes/{dishId}/price")
    public DishPriceResponse setPrice(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long dishId,
                                      @Valid @RequestBody SetDishPriceRequest request) {
        return priceService.setPrice(user.id(), dishId, request);
    }

    @Operation(summary = "A dish's price history", description = "Every price change, oldest first.")
    @GetMapping("/dishes/{dishId}/prices")
    public List<DishPriceResponse> history(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long dishId) {
        return priceService.history(user.id(), dishId);
    }

    @Operation(summary = "Revenue for each day", description = "Each day from 'from' to 'to' inclusive (at most 53 weeks): "
            + "portions, revenue at the prices that applied that day, and whether the stall was closed. For the dashboard's heatmap.")
    @GetMapping("/revenue/daily")
    public DailyRevenueResponse dailyRevenue(@AuthenticationPrincipal AuthenticatedUser user,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return revenueService.daily(user.id(), from, to);
    }

    @Operation(summary = "Revenue and gross profit", description = "For sales from 'from' to 'to' inclusive, at most a year.")
    @GetMapping("/revenue")
    public RevenueResponse revenue(@AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return revenueService.summary(user.id(), from, to);
    }
}
