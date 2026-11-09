package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// Checked by DocumentService, which explains what's wrong (no title, too long) in its error message.
public record DocumentRequest(
        @Schema(description = "Up to 120 characters", example = "Ah Huat Poultry") String title,
        @Schema(description = "Plain text, up to 100,000 characters", example = "Order by 9pm for next-day delivery. Call 9123 4567.") String body
) {}
