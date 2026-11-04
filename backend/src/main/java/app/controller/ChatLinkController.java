package app.controller;

import app.assistant.AssistantException;
import app.chat.ChatChannel;
import app.chat.ChatLinkService;
import app.dto.ChatLinkCodeResponse;
import app.dto.ChatLinksResponse;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Chat apps", description = "Link a Telegram or WhatsApp chat to the account, to record sales from the chat app.")
@RestController
@RequestMapping("/api/chat-links")
public class ChatLinkController {

    private final ChatLinkService chatLinkService;

    public ChatLinkController(ChatLinkService chatLinkService) {
        this.chatLinkService = chatLinkService;
    }

    @Operation(summary = "Chat apps", description = "The chat apps set up on this server, with the account's linked chat on each.")
    @GetMapping
    public ChatLinksResponse list(@AuthenticationPrincipal AuthenticatedUser user) {
        return chatLinkService.list(user.id());
    }

    @Operation(summary = "Get a link code", description = "A one-time code, valid for 10 minutes, and a link that opens the chat app "
            + "with it. Sending the code from a chat links that chat to the account, replacing any chat linked before.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Code issued"),
            @ApiResponse(responseCode = "404", description = "No such channel, or it isn't set up on this server (CHANNEL_NOT_CONFIGURED)", content = @Content)
    })
    @PostMapping("/{channel}")
    public ChatLinkCodeResponse issueCode(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String channel) {
        return chatLinkService.issueCode(user.id(), ChatChannel.fromPath(channel).orElseThrow(() ->
                new AssistantException(HttpStatus.NOT_FOUND, "CHANNEL_NOT_CONFIGURED", "There's no chat app called " + channel + ".")));
    }

    @Operation(summary = "Unlink a chat", description = "The chat can no longer read or record anything for the account.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Unlinked"),
            @ApiResponse(responseCode = "404", description = "No such linked chat on this account", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        chatLinkService.unlink(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
