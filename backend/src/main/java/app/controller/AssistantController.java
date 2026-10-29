package app.controller;

import app.assistant.AssistantService;
import app.dto.*;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Assistant", description = "Voice assistant for recording sales. It only proposes sales; the user confirms them.")
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @Operation(summary = "Assistant status", description = "Whether the assistant and voice input are available on this server.")
    @GetMapping("/status")
    public AssistantStatusResponse status() {
        return assistantService.status();
    }

    @Operation(summary = "Transcribe speech", description = "Turns a short recording (under a minute; webm, mp4/m4a, mp3 or wav) into text. "
            + "The user's dish names are used as hints. Audio is not stored.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transcribed"),
            @ApiResponse(responseCode = "400", description = "Missing, too long or unsupported audio", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content),
            @ApiResponse(responseCode = "502", description = "The speech service failed", content = @Content),
            @ApiResponse(responseCode = "503", description = "Voice input is not available on this server", content = @Content)
    })
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResponse transcribe(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam("audio") MultipartFile audio) {
        return assistantService.transcribe(user.id(), audio);
    }

    @Operation(summary = "Speak text", description = "Returns MP3 audio of an assistant reply or guide step, for reading it aloud.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "MP3 audio", content = @Content(mediaType = "audio/mpeg")),
            @ApiResponse(responseCode = "400", description = "Text missing or over 500 characters", content = @Content),
            @ApiResponse(responseCode = "503", description = "Spoken replies are not available on this server", content = @Content)
    })
    @PostMapping(value = "/speech", produces = "audio/mpeg")
    public ResponseEntity<byte[]> speak(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody SpeechRequest request) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("audio/mpeg")).body(assistantService.speak(user.id(), request.text()));
    }

    @Operation(summary = "Send a message", description = "Sends what the user said or typed. The reply may include a proposal: "
            + "sales to show on a confirm card. Nothing is saved until the proposal is confirmed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reply, with a proposal if the assistant understood sales"),
            @ApiResponse(responseCode = "400", description = "Invalid message", content = @Content),
            @ApiResponse(responseCode = "429", description = "Too many requests", content = @Content),
            @ApiResponse(responseCode = "502", description = "The AI service failed", content = @Content),
            @ApiResponse(responseCode = "503", description = "The assistant is not set up on this server", content = @Content)
    })
    @PostMapping("/messages")
    public AssistantMessageResponse sendMessage(@AuthenticationPrincipal AuthenticatedUser user,
                                                @Valid @RequestBody AssistantMessageRequest request) {
        return assistantService.handleMessage(user.id(), request);
    }

    @Operation(summary = "Confirm a proposal", description = "Saves the proposed sales, or the user's edited version of them. "
            + "Existing quantities for the same dish and day are replaced; new dishes are created. Confirming twice saves once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "An edited entry is invalid", content = @Content),
            @ApiResponse(responseCode = "404", description = "Proposal not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Proposal expired or was cancelled", content = @Content)
    })
    @PostMapping("/proposals/{proposalId}/confirm")
    public ConfirmProposalResponse confirm(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long proposalId,
                                           @Valid @RequestBody(required = false) ConfirmProposalRequest request) {
        return assistantService.confirmProposal(user.id(), proposalId, request);
    }

    @Operation(summary = "Cancel a proposal", description = "Discards proposed sales without saving them.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cancelled"),
            @ApiResponse(responseCode = "404", description = "Proposal not found", content = @Content),
            @ApiResponse(responseCode = "409", description = "Proposal was already saved", content = @Content)
    })
    @PostMapping("/proposals/{proposalId}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long proposalId) {
        assistantService.cancelProposal(user.id(), proposalId);
        return ResponseEntity.noContent().build();
    }
}
