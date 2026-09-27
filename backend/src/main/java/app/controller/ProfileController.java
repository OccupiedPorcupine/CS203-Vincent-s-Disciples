package app.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import app.dto.ProfileResponse;
import app.user.AuthenticatedUser;

@Tag(name = "Profile", description = "Authenticated user profile operations")
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    @Operation(summary = "Get user profile", description = "Returns the profile of the currently authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "User is not authenticated", content = @Content)
    })
    @GetMapping
    public ResponseEntity<ProfileResponse> profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok(ProfileResponse.from(user));
    }
}
