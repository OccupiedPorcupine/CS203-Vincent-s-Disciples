package app.assistant.guide;

import io.swagger.v3.oas.annotations.media.Schema;

// One step of a guided tour: highlight guideId on page and show message.
public record GuideStep(
        @Schema(description = "Page ID", example = "data") String page,
        @Schema(description = "Page URL, for moving between pages", example = "data.html") String url,
        @Schema(description = "data-guide-id of the element to highlight", example = "upload-dropzone") String guideId,
        @Schema(description = "What to tell the user", example = "Drop your CSV or Excel file here.") String message
) {}
