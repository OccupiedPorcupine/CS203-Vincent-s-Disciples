package app.controller;

import app.actions.ActionService;
import app.dto.ActionCard;
import app.dto.DismissActionRequest;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Actions", description = "The dashboard's Actions needed cards")
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final ActionService actionService;

    public ActionController(ActionService actionService) {
        this.actionService = actionService;
    }

    @Operation(summary = "List actions", description = "Things the stall should do, most urgent first: days without "
            + "sales, files not imported, dishes without a price, an upcoming public holiday. Dismissed cards are left out.")
    @GetMapping
    public List<ActionCard> actions(@AuthenticationPrincipal AuthenticatedUser user) {
        return actionService.actions(user.id());
    }

    @Operation(summary = "Dismiss an action", description = "Hides that card for good; a new card of the same kind still shows.")
    @PostMapping("/dismiss")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody DismissActionRequest request) {
        actionService.dismiss(user.id(), request.key());
        return ResponseEntity.noContent().build();
    }
}
