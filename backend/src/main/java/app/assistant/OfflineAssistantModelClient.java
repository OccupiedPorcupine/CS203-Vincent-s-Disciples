package app.assistant;

import app.dish.DishAlias;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A rule-based stand-in for the language model (ASSISTANT_PROVIDER=offline), for demos,
// development and tests without an OpenAI key. It is not AI: it only understands typed sales
// like "yesterday chicken rice 42, char siew 30", closures like "closed tomorrow" (today,
// tomorrow or yesterday only), "what do I prep for tomorrow?", and "how do I ..." questions that
// share words with a task in the app guide. It drives the same tools a real model would (list_dishes
// then propose_sales_entry and/or propose_closure, get_prep_list, or guide_user), so the rest of the
// pipeline is real.
public class OfflineAssistantModelClient implements AssistantModelClient {

    private static final Pattern TODAY = Pattern.compile("Today is (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern YESTERDAY = Pattern.compile("Yesterday was (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern TOMORROW = Pattern.compile("Tomorrow is (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[,，;；、\\n]|\\band\\b|\\bthen\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern YESTERDAY_WORD = Pattern.compile("yesterday|ytd|昨天|昨日", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOMORROW_WORD = Pattern.compile("tomorrow|tmr|明天|明日", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSED = Pattern.compile(
            "\\b(closed?|closing|not open|no business)\\b|休息|不开|关门|没开|清洗", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_AGAIN = Pattern.compile(
            "\\bopen\\b.*\\b(after all|again)\\b|\\breopen|照常开|还是开", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLEANING = Pattern.compile("clean|清洗", Pattern.CASE_INSENSITIVE);
    // "every Tuesday", 每个星期二: a weekly rest day, which is set on the Data page, not proposed.
    private static final Pattern EVERY_WEEKDAY = Pattern.compile(
            "\\bevery (mon|tues|wednes|thurs|fri|satur|sun)day|每个?(星期|礼拜)|每周", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILLER = Pattern.compile(
            "\\b(today|yesterday|ytd|sold|sell|sells|selling|plates?|portions?|packets?|bowls?|lor|leh|lah|ah)\\b|今天|今日|昨天|昨日|卖了|卖|份|碗|盘",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PREP = Pattern.compile("\\b(prep|prepare)\\b|准备|备料", Pattern.CASE_INSENSITIVE);
    private static final Pattern TODAY_WORD = Pattern.compile("\\btoday\\b|今天|今日", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUESTION = Pattern.compile(
            "\\b(how|where|show me|help|guide)\\b|\\?|怎么|哪里|如何", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("[a-z]{3,}");
    // "char siew rice is now $5", "chicken rice $4.80": a dish, then an amount in dollars.
    private static final Pattern PRICE = Pattern.compile("^(.*?)\\s*(?:S\\$|\\$)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_WORDS = Pattern.compile("\\b(is|now|price|the|of|to|change|set)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "how", "can", "where", "what", "and", "for", "you", "your", "with", "from", "this", "that",
            "show", "help", "guide", "into", "use", "get", "want", "page", "one", "day");
    private static final String GUIDE_MARKER = "App guide index, with the current page in full under current_page (JSON):";

    private static final String HELP = "Offline demo mode: I only understand typed sales such as \"chicken rice 42, char siew rice 30\", "
            + "closures such as \"closed tomorrow\", \"what do I prep for tomorrow?\", or questions like \"how do I upload data?\".";

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
    public byte[] speak(String text) {
        throw new AssistantException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_UNAVAILABLE",
                "Spoken replies need the OpenAI key on the server.");
    }

    @Override
    public ModelResponse respond(String instructions, List<JsonNode> input, List<ToolSpec> tools) {
        JsonNode last = input.getLast();

        if (!"function_call_output".equals(last.path("type").asString())) {
            String text = lastUserText(input);
            if (PREP.matcher(text).find()) {
                ObjectNode arguments = jsonMapper.createObjectNode();
                arguments.put("date", find(TODAY_WORD.matcher(text).find() ? TODAY : TOMORROW, instructions));
                return call(AssistantTools.GET_PREP_LIST, arguments);
            }
            String taskId = EVERY_WEEKDAY.matcher(text).find() && CLOSED.matcher(text).find() ? "set-opening-days"
                    : QUESTION.matcher(text).find() ? matchTask(text, instructions) : null;
            if (taskId != null) {
                ObjectNode arguments = jsonMapper.createObjectNode();
                arguments.put("task_id", taskId);
                arguments.putNull("steps");
                return call(AssistantTools.GUIDE_USER, arguments);
            }
            return call(AssistantTools.LIST_DISHES, jsonMapper.createObjectNode());
        }

        String toolName = toolNameFor(last.path("call_id").asString(), input);
        JsonNode output = jsonMapper.readTree(last.path("output").asString());

        if (AssistantTools.LIST_DISHES.equals(toolName)) {
            Matcher price = PRICE.matcher(lastUserText(input).strip());
            if (price.find()) {
                JsonNode dish = matchDish(PRICE_WORDS.matcher(price.group(1)).replaceAll(" ").strip(), output);
                if (dish == null) {
                    return message("Which dish is that price for?");
                }
                ObjectNode arguments = jsonMapper.createObjectNode();
                arguments.put("dish_id", dish.path("id").asLong());
                arguments.put("price", new java.math.BigDecimal(price.group(2)));
                arguments.putNull("unit_cost");
                arguments.putNull("effective_from");
                return call(AssistantTools.PROPOSE_PRICE_CHANGE, arguments);
            }
            ArrayNode entries = parseSales(lastUserText(input), output, instructions);
            if (!entries.isEmpty()) {
                ObjectNode arguments = jsonMapper.createObjectNode();
                arguments.set("entries", entries);
                return call(AssistantTools.PROPOSE_SALES_ENTRY, arguments);
            }
            ObjectNode closure = parseClosure(lastUserText(input), instructions);
            return closure == null ? message(HELP) : proposeClosure(closure);
        }

        // Sales and a closure in one message: the closure goes on the same card.
        if (AssistantTools.PROPOSE_SALES_ENTRY.equals(toolName) && output.path("ok").asBoolean()) {
            ObjectNode closure = parseClosure(lastUserText(input), instructions);
            if (closure != null) {
                return proposeClosure(closure);
            }
        }

        if (AssistantTools.GET_PREP_LIST.equals(toolName)) {
            return message(prepListReply(output));
        }

        if (output.path("ok").asBoolean()) {
            return message(AssistantTools.GUIDE_USER.equals(toolName) ? "Let me show you."
                    : AssistantTools.PROPOSE_PRICE_CHANGE.equals(toolName) ? "Please check the new price and press Confirm to save it."
                    : "Please check these and press Confirm to save them.");
        }
        List<String> problems = new ArrayList<>();
        output.path("problems").forEach(problem -> problems.add(problem.asString()));
        if (output.has("error")) problems.add(output.path("error").asString());
        return message("I couldn't use that: " + String.join(" ", problems));
    }

    private static String prepListReply(JsonNode prepList) {
        if (prepList.has("error")) return prepList.path("error").asString();
        if (prepList.has("closed")) return prepList.path("closed").asString();
        if (prepList.path("dishes").isEmpty()) return "Record some sales first, then I can tell you what to prepare.";

        List<String> dishes = new ArrayList<>();
        prepList.path("dishes").forEach(dish -> dishes.add(dish.path("dish").asString() + " " + dish.path("portions").asInt()));
        List<String> ingredients = new ArrayList<>();
        prepList.path("ingredients").forEach(ingredient -> ingredients.add(ingredient.path("ingredient").asString() + " "
                + ingredient.path("quantity").asString() + " " + ingredient.path("unit").asString()));
        return "Prepare " + String.join(", ", dishes) + "."
                + (ingredients.isEmpty() ? " Add recipes on the Prep list page to see the ingredients." : " You need " + String.join(", ", ingredients) + ".");
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
            if (quantity == null || phrase.isEmpty() || CLOSED.matcher(segment).find() || OPEN_AGAIN.matcher(segment).find()) {
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

    // "closed tomorrow", "today closed for centre cleaning", "open tomorrow after all"; null if the
    // message says nothing about being closed or open.
    private ObjectNode parseClosure(String text, String instructions) {
        boolean openAgain = OPEN_AGAIN.matcher(text).find();
        if (!openAgain && !CLOSED.matcher(text).find()) {
            return null;
        }
        LocalDate today = LocalDate.parse(find(TODAY, instructions));
        LocalDate date = TOMORROW_WORD.matcher(text).find() ? today.plusDays(1)
                : YESTERDAY_WORD.matcher(text).find() ? today.minusDays(1)
                : today;

        ObjectNode closure = jsonMapper.createObjectNode();
        closure.put("date", date.toString());
        closure.put("closed", !openAgain);
        if (!openAgain && CLEANING.matcher(text).find()) {
            closure.put("reason", "Centre cleaning");
        } else {
            closure.putNull("reason");
        }
        return closure;
    }

    private ModelResponse proposeClosure(ObjectNode closure) {
        ObjectNode arguments = jsonMapper.createObjectNode();
        arguments.putArray("closures").add(closure);
        return call(AssistantTools.PROPOSE_CLOSURE, arguments);
    }

    // The task whose ID and summary share the most words with the question, from the guide index
    // in the instructions. Null if none share a word.
    private String matchTask(String text, String instructions) {
        int markerAt = instructions.indexOf(GUIDE_MARKER);
        if (markerAt < 0) {
            return null;
        }
        String guideLine = instructions.substring(markerAt + GUIDE_MARKER.length()).strip().lines().findFirst().orElse("{}");
        Set<String> asked = words(text);

        String best = null;
        int bestScore = 0;
        for (JsonNode task : jsonMapper.readTree(guideLine).path("tasks")) {
            Set<String> taskWords = words(task.path("id").asString().replace('-', ' ') + " " + task.path("summary").asString());
            int score = (int) asked.stream().filter(taskWords::contains).count();
            if (score > bestScore) {
                best = task.path("id").asString();
                bestScore = score;
            }
        }
        return best;
    }

    // Lowercase words of 3+ letters, without common words, with a plural or -ing ending removed.
    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        Matcher matcher = WORD.matcher(text.toLowerCase());
        while (matcher.find()) {
            if (!STOP_WORDS.contains(matcher.group())) {
                words.add(matcher.group().replaceAll("(ing|s)$", ""));
            }
        }
        return words;
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
