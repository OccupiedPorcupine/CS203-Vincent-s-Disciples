package app.assistant.guide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Sends the index of all pages and tasks (one line each) plus the current page in full.
// Retrieval without vectors: the model calls get_guide for anything else it needs.
@Component
public class IndexGuideRetriever implements GuideRetriever {

    private static final Logger log = LoggerFactory.getLogger(IndexGuideRetriever.class);

    private final AppGuide appGuide;
    private final JsonMapper jsonMapper;

    public IndexGuideRetriever(AppGuide appGuide, JsonMapper jsonMapper) {
        this.appGuide = appGuide;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String context(String userText, String currentPage) {
        ObjectNode context = appGuide.index(jsonMapper);
        appGuide.page(currentPage).ifPresent(page -> context.set("current_page", page));
        String json = jsonMapper.writeValueAsString(context);

        // Rough size (about 4 characters a token), to see when the guide outgrows this approach (#33).
        log.info("Guide context for page {}: {} characters, about {} tokens", currentPage, json.length(), json.length() / 4);
        return json;
    }
}
