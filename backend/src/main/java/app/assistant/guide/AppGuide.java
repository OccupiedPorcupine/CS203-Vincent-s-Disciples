package app.assistant.guide;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

// The assistant's knowledge of Chicky's screens, loaded from assistant/app-guide.json.
// Pages and tasks are separate sections with IDs and one-line summaries, so a GuideRetriever
// can choose which sections to send to the model (see #33 for vector retrieval).
@Component
public class AppGuide {

    // A step's page is "*" when it works on any page, e.g. the assistant button.
    public static final String ANY_PAGE = "*";
    static final String RESOURCE = "assistant/app-guide.json";

    private final JsonNode guide;
    private final Map<String, JsonNode> pages = new LinkedHashMap<>();
    private final Map<String, JsonNode> tasks = new LinkedHashMap<>();
    private final Set<String> sharedElements = new HashSet<>();

    public AppGuide(JsonMapper jsonMapper) throws IOException {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            this.guide = jsonMapper.readTree(input);
        }
        guide.path("pages").forEach(page -> pages.put(page.path("id").asString(), page));
        guide.path("tasks").forEach(task -> tasks.put(task.path("id").asString(), task));
        guide.path("shared").forEach(element -> sharedElements.add(element.path("guideId").asString()));
    }

    public JsonNode raw() {
        return guide;
    }

    // Short list of every page and task, always sent to the model.
    public ObjectNode index(JsonMapper jsonMapper) {
        ObjectNode index = jsonMapper.createObjectNode();
        ArrayNode pageIndex = index.putArray("pages");
        pages.values().forEach(page -> pageIndex.addObject()
                .put("id", page.path("id").asString())
                .put("title", page.path("title").asString())
                .put("summary", page.path("summary").asString()));
        ArrayNode taskIndex = index.putArray("tasks");
        tasks.values().forEach(task -> taskIndex.addObject()
                .put("id", task.path("id").asString())
                .put("summary", task.path("summary").asString()));
        index.set("shared", guide.path("shared"));
        return index;
    }

    public Optional<JsonNode> page(String id) {
        return Optional.ofNullable(pages.get(id));
    }

    public Optional<JsonNode> task(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public Optional<JsonNode> section(String id) {
        return page(id).or(() -> task(id));
    }

    public boolean hasElement(String pageId, String guideId) {
        if (sharedElements.contains(guideId)) {
            return true;
        }
        return page(pageId).map(page -> {
            for (JsonNode element : page.path("elements")) {
                if (element.path("guideId").asString().equals(guideId)) return true;
            }
            return false;
        }).orElse(false);
    }

    public String url(String pageId) {
        return page(pageId).map(page -> page.path("url").asString()).orElse(null);
    }

    public String title(String pageId) {
        return page(pageId).map(page -> page.path("title").asString()).orElse(pageId);
    }

    // The task's steps, with "*" pages resolved to the current page. If the task starts on another
    // page, a first step points at the link to it when the current page has one ("nav-<page>").
    public List<GuideStep> stepsFor(String taskId, String currentPage) {
        JsonNode task = tasks.get(taskId);
        if (task == null) {
            return List.of();
        }

        List<GuideStep> steps = new ArrayList<>();
        for (JsonNode step : task.path("steps")) {
            String page = step.path("page").asString();
            steps.add(step(ANY_PAGE.equals(page) ? currentPage : page, step.path("guideId").asString(), step.path("message").asString()));
        }

        String firstPage = steps.isEmpty() ? currentPage : steps.getFirst().page();
        if (!firstPage.equals(currentPage) && hasElement(currentPage, "nav-" + firstPage)) {
            steps.addFirst(step(currentPage, "nav-" + firstPage, "Open " + title(firstPage) + " from here."));
        }
        return steps;
    }

    public GuideStep step(String page, String guideId, String message) {
        return new GuideStep(page, url(page), guideId, message);
    }
}
