package app.assistant;

import app.dish.DishAlias;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A rule-based stand-in for the language model (ASSISTANT_PROVIDER=offline), for demos,
// development and tests without an OpenAI key. It is not AI: it only understands typed sales
// like "yesterday chicken rice 42, char siew 30". It drives the same tools in the same order a
// real model would (list_dishes, then propose_sales_entry), so the rest of the pipeline is real.
public class OfflineAssistantModelClient implements AssistantModelClient {

    private static final Pattern TODAY = Pattern.compile("Today is (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern YESTERDAY = Pattern.compile("Yesterday was (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[,，;；、\\n]|\\band\\b|\\bthen\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern YESTERDAY_WORD = Pattern.compile("yesterday|ytd|昨天|昨日", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER = Pattern.compile(
            "\\b(today|yesterday|ytd|sold|sell|sells|selling|plates?|portions?|packets?|bowls?|lor|leh|lah|ah)\\b|今天|今日|昨天|昨日|卖了|卖|份|碗|盘",
            Pattern.CASE_INSENSITIVE);

    private static final String HELP = "Offline demo mode: I only understand typed sales such as \"chicken rice 42, char siew rice 30\" or \"yesterday laksa 12\".";

    private final JsonMapper jsonMapper;
    private final AtomicLong callIds = new AtomicLong();

    public OfflineAssistantModelClient(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String modelName() {
        return "offline-rules";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean supportsVoice() {
        return false;
    }

    @Override
    public Transcription transcribe(byte[] audio, String filename, List<String> keywords) {
        throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_UNAVAILABLE",
                "Voice input needs the OpenAI key on the server. Please type instead.");
    }

    @Override
    public ModelResponse respond(String instructions, List<JsonNode> input, List<ToolSpec> tools) {
        JsonNode last = input.getLast();

        if (!"function_call_output".equals(last.path("type").asString())) {
            return call(AssistantTools.LIST_DISHES, jsonMapper.createObjectNode());
        }

        String toolName = toolNameFor(last.path("call_id").asString(), input);
        JsonNode output = jsonMapper.readTree(last.path("output").asString());

        if (AssistantTools.LIST_DISHES.equals(toolName)) {
            ArrayNode entries = parseSales(lastUserText(input), output, instructions);
            if (entries.isEmpty()) {
                return message(HELP);
            }
            ObjectNode arguments = jsonMapper.createObjectNode();
            arguments.set("entries", entries);
            return call(AssistantTools.PROPOSE_SALES_ENTRY, arguments);
        }

        if (output.path("ok").asBoolean()) {
            return message("Please check these and press Confirm to save them.");
        }
        List<String> problems = new ArrayList<>();
        output.path("problems").forEach(problem -> problems.add(problem.asString()));
        return message("I couldn't use that: " + String.join(" ", problems));
    }

    private ArrayNode parseSales(String text, JsonNode dishes, String instructions) {
        String date = find(YESTERDAY_WORD.matcher(text).find() ? YESTERDAY : TODAY, instructions);
        ArrayNode entries = jsonMapper.createArrayNode();

        for (String segment : SEGMENT_SEPARATOR.split(text)) {
            Matcher number = NUMBER.matcher(segment);
            String quantity = null;
            while (number.find()) {
                quantity = number.group();
            }
            String phrase = FILLER.matcher(NUMBER.matcher(segment).replaceAll(" ")).replaceAll(" ").strip().replaceAll("\\s+", " ");
            if (quantity == null || phrase.isEmpty()) {
                continue;
            }

            ObjectNode entry = entries.addObject();
            JsonNode dish = matchDish(phrase, dishes);
            if (dish == null) {
                entry.putNull("dish_id");
                entry.put("new_dish_name", phrase);
            } else {
                entry.put("dish_id", dish.path("id").asLong());
                entry.putNull("new_dish_name");
            }
            entry.put("spoken_name", phrase);
            entry.put("date", date);
            entry.put("quantity", Integer.parseInt(quantity));
        }
        return entries;
    }

    // Exact name or other name first; otherwise the only dish whose name contains the phrase
    // ("char siew" → "Char siew rice").
    private static JsonNode matchDish(String phrase, JsonNode dishes) {
        String spoken = DishAlias.normalize(phrase);
        List<JsonNode> partial = new ArrayList<>();

        for (JsonNode dish : dishes) {
            List<String> names = new ArrayList<>(List.of(dish.path("name").asString()));
            dish.path("other_names").forEach(name -> names.add(name.asString()));
            for (String name : names) {
                String normalized = DishAlias.normalize(name);
                if (normalized.equals(spoken)) {
                    return dish;
                }
                if (normalized.contains(spoken)) {
                    partial.add(dish);
                    break;
                }
            }
        }
        return partial.size() == 1 ? partial.getFirst() : null;
    }

    private static String lastUserText(List<JsonNode> input) {
        for (JsonNode item : input.reversed()) {
            if ("user".equals(item.path("role").asString())) {
                return item.path("content").asString();
            }
        }
        return "";
    }

    private static String toolNameFor(String callId, List<JsonNode> input) {
        for (JsonNode item : input.reversed()) {
            if ("function_call".equals(item.path("type").asString()) && callId.equals(item.path("call_id").asString())) {
                return item.path("name").asString();
            }
        }
        return "";
    }

    private static String find(Pattern pattern, String instructions) {
        Matcher matcher = pattern.matcher(instructions);
        if (!matcher.find()) {
            throw new IllegalStateException("The instructions don't state the date");
        }
        return matcher.group(1);
    }

    private ModelResponse call(String name, JsonNode arguments) {
        ObjectNode item = jsonMapper.createObjectNode();
        String callId = "offline_" + callIds.incrementAndGet();
        item.put("type", "function_call");
        item.put("call_id", callId);
        item.put("name", name);
        item.put("arguments", jsonMapper.writeValueAsString(arguments));
        return new ModelResponse(List.of(item), List.of(new ToolCall(callId, name, arguments)), "");
    }

    private ModelResponse message(String text) {
        ObjectNode item = jsonMapper.createObjectNode();
        item.put("type", "message");
        item.put("role", "assistant");
        item.putArray("content").addObject().put("type", "output_text").put("text", text);
        return new ModelResponse(List.of(item), List.of(), text);
    }
}
