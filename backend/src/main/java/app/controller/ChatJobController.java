package app.controller;

import app.chat.ChatReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

// Sends the chat reminders and prep lists that have come due (issue #101). Called every hour by
// .github/workflows/chat-reminders.yml, not by browsers: public and CSRF-exempt (SecurityConfig),
// and trusted only with "Authorization: Bearer <CRON_SECRET>". Refused while CRON_SECRET isn't set.
@Tag(name = "Chat apps")
@RestController
public class ChatJobController {

    private static final Logger log = LoggerFactory.getLogger(ChatJobController.class);

    public static final String PATH = "/api/channels/jobs/run";

    private final ChatReminderService reminders;
    private final byte[] expected;

    public ChatJobController(ChatReminderService reminders, @Value("${app.chat.cron-secret:}") String cronSecret) {
        this.reminders = reminders;
        this.expected = cronSecret.isBlank() ? null : ("Bearer " + cronSecret.strip()).getBytes(StandardCharsets.UTF_8);
    }

    @Operation(summary = "Send due chat reminders", description = "Sends each linked chat's sales reminder and "
            + "prep list that is due today and not sent yet. For the scheduler, with the CRON_SECRET bearer token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ran; the counts say what was sent"),
            @ApiResponse(responseCode = "401", description = "Missing or wrong token, or CRON_SECRET isn't set")
    })
    @PostMapping(PATH)
    public ResponseEntity<ChatReminderService.Run> run(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        // Compared in constant time, so the token can't be guessed a character at a time.
        if (expected == null || authorization == null
                || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Chat job call refused: wrong or missing token");
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(reminders.run());
    }
}
