package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpeechRequest(
        @NotBlank @Size(max = 500) @Schema(description = "Text to read aloud", example = "Please check these and press Confirm to save them.") String text
) {}
