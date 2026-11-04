package app.controller;

import app.dto.WeeklySummaryResponse;
import app.price.WeeklySummaryService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Summary", description = "The week's sales, revenue and leftovers in a few sentences")
@RestController
@RequestMapping("/api/summary")
public class SummaryController {

    private final WeeklySummaryService summaryService;

    public SummaryController(WeeklySummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @Operation(summary = "Weekly summary", description = "The 7 days ending on the latest recorded sale, as on the dashboard: "
            + "portions against the 7 days before, revenue, the best-selling dish, leftovers and the dish that sold out most, "
            + "with the same figures as GET /api/revenue and GET /api/waste, and the summary in English and Chinese to read aloud.")
    @GetMapping("/weekly")
    public WeeklySummaryResponse weekly(@AuthenticationPrincipal AuthenticatedUser user) {
        return summaryService.weekly(user.id());
    }
}
