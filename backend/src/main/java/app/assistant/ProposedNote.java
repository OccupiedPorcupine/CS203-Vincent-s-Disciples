package app.assistant;

import io.swagger.v3.oas.annotations.media.Schema;

// A note the assistant offers to save to the owner's documents (issue #154), shown on a confirm
// card. Nothing is saved until the owner confirms; documentId is set once it is.
public record ProposedNote(
        @Schema(description = "The saved document's ID; null until confirmed") Long documentId,
        @Schema(example = "Friday hours in Ramadan") String title,
        @Schema(example = "Close at 2pm on Fridays during Ramadan.") String text
) {}
