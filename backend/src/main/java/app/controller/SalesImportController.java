package app.controller;

import app.dto.SalesImportPreviewResponse;
import app.dto.SalesImportRequest;
import app.dto.SalesImportResponse;
import app.salesimport.SalesImportService;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Sales import", description = "Turn an uploaded sales file into daily sales")
@RestController
@RequestMapping("/api/data-sources/{sourceId}")
public class SalesImportController {

    private final SalesImportService service;

    public SalesImportController(SalesImportService service) {
        this.service = service;
    }

    @Operation(summary = "Preview an import", description = "Reads the uploaded file and says what importing it would do: "
            + "rows, dates, how its dish names match your dishes, dates that already have sales, and row errors. Writes nothing.")
    @GetMapping("/import-preview")
    public SalesImportPreviewResponse preview(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long sourceId) {
        return service.preview(user.id(), sourceId);
    }

    @Operation(summary = "Import into daily sales", description = "Writes the file's rows as daily sales in one transaction. "
            + "Every name that matches no dish needs a choice: an existing dish, a new dish, or leave it out.")
    @PostMapping("/import")
    public SalesImportResponse importSales(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long sourceId,
                                           @Valid @RequestBody SalesImportRequest request) {
        return service.importSales(user.id(), sourceId, request);
    }
}
