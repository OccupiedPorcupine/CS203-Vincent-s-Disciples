package app.assistant.guide;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// Keeps assistant/app-guide.json and the frontend in step, so the assistant never points at a
// button that no longer exists and new UI doesn't go unexplained. If this fails after a UI
// change, update app-guide.json (or the data-guide-id attributes) to match.
class AppGuideConsistencyTest {

    private static final Path FRONTEND = Path.of("..", "frontend");
    private static final Pattern GUIDE_ID = Pattern.compile("data-guide-id=\"([^\"]+)\"");
    // assistant.js adds the shared elements to every page from JavaScript.
    private static final Pattern SCRIPT_GUIDE_ID = Pattern.compile("\"data-guide-id\":\\s*\"([^\"]+)\"");

    private final AppGuide guide = new AppGuide(JsonMapper.builder().build());

    AppGuideConsistencyTest() throws IOException {
    }

    @Test
    void everyGuideElementIsOnItsPageAndEveryTaggedElementIsInTheGuide() throws IOException {
        List<String> problems = new ArrayList<>();

        for (JsonNode page : guide.raw().path("pages")) {
            String url = page.path("url").asString();
            Set<String> inHtml = matches(GUIDE_ID, Files.readString(FRONTEND.resolve(url)));
            Set<String> inGuide = new HashSet<>();
            page.path("elements").forEach(element -> inGuide.add(element.path("guideId").asString()));

            inGuide.stream().filter(id -> !inHtml.contains(id))
                    .forEach(id -> problems.add(url + ": app-guide.json lists \"" + id + "\" but the page has no data-guide-id=\"" + id + "\""));
            inHtml.stream().filter(id -> !inGuide.contains(id))
                    .forEach(id -> problems.add(url + ": data-guide-id=\"" + id + "\" is not described in app-guide.json"));
        }

        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void sharedElementsAreAddedByTheAssistantWidget() throws IOException {
        Set<String> inScript = matches(SCRIPT_GUIDE_ID, Files.readString(FRONTEND.resolve("js/assistant.js")));

        guide.raw().path("shared").forEach(element ->
                assertTrue(inScript.contains(element.path("guideId").asString()),
                        "assistant.js should set data-guide-id \"" + element.path("guideId").asString() + "\""));
    }

    @Test
    void everyTaskStepPointsAtAnElementOnItsPage() {
        for (JsonNode task : guide.raw().path("tasks")) {
            assertFalse(task.path("summary").asString().isBlank(), task.path("id").asString() + " needs a summary");
            for (JsonNode step : task.path("steps")) {
                String page = step.path("page").asString();
                String guideId = step.path("guideId").asString();
                boolean exists = AppGuide.ANY_PAGE.equals(page)
                        ? guide.hasElement("", guideId)
                        : guide.hasElement(page, guideId);
                assertTrue(exists, task.path("id").asString() + ": no " + guideId + " on page " + page);
            }
        }
    }

    @Test
    void aTaskOnAnotherPageStartsAtTheLinkToIt() {
        List<GuideStep> steps = guide.stepsFor("upload-training-data", "dashboard");

        assertEquals(new GuideStep("dashboard", "dashboard.html", "nav-data", "Open Data ingestion from here."), steps.getFirst());
        assertEquals("upload-dropzone", steps.get(1).guideId());
        assertEquals("data.html", steps.get(1).url());
    }

    @Test
    void aTaskForAnyPageStaysOnTheCurrentPage() {
        assertEquals("forecast", guide.stepsFor("record-sales", "forecast").getFirst().page());
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }
}
