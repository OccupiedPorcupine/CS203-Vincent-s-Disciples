package app.controller;

import app.assistant.AssistantPlan;
import app.assistant.AssistantUsageService;
import app.dto.AccountUsageResponse;
import app.dto.AssistantUsageReportResponse;
import app.dto.ChangePlanRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "Admin", description = "For accounts with ROLE_ADMIN only: what the assistant costs, and account plans.")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    static final int MAX_DAYS = 366;
    static final int MAX_ACCOUNTS = 200;

    private final AssistantUsageService usageService;
    private final Clock clock;

    public AdminController(AssistantUsageService usageService, Clock clock) {
        this.usageService = usageService;
        this.clock = clock;
    }

    @Operation(summary = "Assistant cost by account", description = "The accounts whose assistant use cost the most over "
            + "the last `days` days (Singapore time, today included). Costs are estimates at the list prices in app.assistant.prices.*.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Highest estimated cost first"),
            @ApiResponse(responseCode = "400", description = "days or limit out of range", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not an admin", content = @Content)
    })
    @GetMapping("/assistant-usage")
    public AssistantUsageReportResponse assistantUsage(@RequestParam(defaultValue = "30") int days,
                                                       @RequestParam(defaultValue = "20") int limit) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("days must be from 1 to " + MAX_DAYS + ".");
        }
        if (limit < 1 || limit > MAX_ACCOUNTS) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_ACCOUNTS + ".");
        }
        List<AccountUsageResponse> accounts = usageService.topAccounts(days, limit);
        LocalDate today = LocalDate.now(clock);
        BigDecimal total = accounts.stream().map(AccountUsageResponse::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AssistantUsageReportResponse(today.minusDays(days - 1L), today, total, accounts);
    }

    @Operation(summary = "Change an account's plan", description = "FREE, STANDARD or PRO. The new allowances apply to the "
            + "account's next request, including the rest of today.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed"),
            @ApiResponse(responseCode = "400", description = "Unknown plan or account", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not an admin", content = @Content)
    })
    @PutMapping("/users/{userId}/plan")
    public Map<String, Object> changePlan(@PathVariable Long userId, @Valid @RequestBody ChangePlanRequest request) {
        AssistantPlan plan = usageService.changePlan(userId, request.plan());
        return Map.of("userId", userId, "plan", plan.name());
    }
}
