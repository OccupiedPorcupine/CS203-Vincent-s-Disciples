package app.assistant;

import app.assistant.AssistantModelClient.ToolCall;
import app.assistant.AssistantModelClient.ToolSpec;
import app.assistant.guide.AppGuide;
import app.assistant.guide.GuideStep;
import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.PrepListResponse;
import app.dto.ProposalEntryInput;
import app.exception.InvalidForecastDateException;
import app.sale.DailySaleRepository;
import app.service.PrepListService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// The functions the language model may call. None of them write to the database: the most the
// model can do is propose sales, which the user then confirms. The user's ID comes from the
// session (TurnContext), never from the model.
@Component
public class AssistantTools {

    static final String LIST_DISHES = "list_dishes";
    static final String GET_SALES = "get_sales";
    static final String PROPOSE_SALES_ENTRY = "propose_sales_entry";
    static final String GET_GUIDE = "get_guide";
    static final String GUIDE_USER = "guide_user";
    static final String GET_PREP_LIST = "get_prep_list";
    private static final int MAX_SALES_DAYS = 120;
    private static final int MAX_GUIDE_STEPS = 8;
    private static final int MAX_STEP_MESSAGE = 240;

    private final DishRepository dishRepository;
    private final DishAliasRepository dishAliasRepository;
    private final DailySaleRepository dailySaleRepository;
    private final ProposalValidator proposalValidator;
    private final PrepListService prepListService;
    private final AppGuide appGuide;
    private final JsonMapper jsonMapper;
    private final List<ToolSpec> specs;

    // What a turn's tool calls have produced so far. The last valid proposal and guide in a turn
    // are the ones shown. liveElementIds are elements on the user's current page that the app
    // guide doesn't describe yet, which guide_user may also point at.
    public static class TurnContext {
        final Long ownerId;
        final LocalDate today;
        final String page;
        final Set<String> liveElementIds;
        List<ProposedEntry> proposal;
        List<GuideStep> guide;

        public TurnContext(Long ownerId, LocalDate today, String page, Set<String> liveElementIds) {
            this.ownerId = ownerId;
            this.today = today;
            this.page = page;
            this.liveElementIds = liveElementIds;
        }

        public List<ProposedEntry> proposal() {
            return proposal;
        }

        public List<GuideStep> guide() {
            return guide;
        }
    }

    public AssistantTools(DishRepository dishRepository, DishAliasRepository dishAliasRepository,
                          DailySaleRepository dailySaleRepository, ProposalValidator proposalValidator,
                          PrepListService prepListService, AppGuide appGuide, JsonMapper jsonMapper) {
        this.dishRepository = dishRepository;
        this.dishAliasRepository = dishAliasRepository;
        this.dailySaleRepository = dailySaleRepository;
        this.proposalValidator = proposalValidator;
        this.prepListService = prepListService;
        this.appGuide = appGuide;
        this.jsonMapper = jsonMapper;
        this.specs = List.of(
                new ToolSpec(LIST_DISHES,
                        "List the user's dishes with their IDs and the other names the user calls them.",
                        schema("""
                                {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
                                """)),
                new ToolSpec(GET_SALES,
                        "Get recorded daily sales for one of the user's dishes between two dates (inclusive, at most "
                                + MAX_SALES_DAYS + " days).",
                        schema("""
                                {"type": "object",
                                 "properties": {
                                   "dish_id": {"type": "integer"},
                                   "from": {"type": "string", "description": "YYYY-MM-DD"},
                                   "to": {"type": "string", "description": "YYYY-MM-DD"}},
                                 "required": ["dish_id", "from", "to"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(PROPOSE_SALES_ENTRY,
                        "Show the user a confirm card with the daily sales you understood from their message. "
                                + "This does NOT save anything; the user checks the card and confirms it. "
                                + "Include every entry from the message in one call. Use dish_id for an existing dish, "
                                + "or new_dish_name (with dish_id null) for a dish the user doesn't have yet.",
                        schema("""
                                {"type": "object",
                                 "properties": {
                                   "entries": {"type": "array", "items": {
                                     "type": "object",
                                     "properties": {
                                       "dish_id": {"type": ["integer", "null"]},
                                       "new_dish_name": {"type": ["string", "null"]},
                                       "spoken_name": {"type": ["string", "null"], "description": "The words the user used for the dish, exactly as said"},
                                       "date": {"type": "string", "description": "YYYY-MM-DD"},
                                       "quantity": {"type": "integer", "description": "Portions sold"}},
                                     "required": ["dish_id", "new_dish_name", "spoken_name", "date", "quantity"],
                                     "additionalProperties": false}}},
                                 "required": ["entries"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(GET_GUIDE,
                        "Get the full app guide section for a page or task ID from the guide index: "
                                + "a page's elements, or a task's steps.",
                        schema("""
                                {"type": "object",
                                 "properties": {"id": {"type": "string"}},
                                 "required": ["id"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(GUIDE_USER,
                        "Show the user where to go: dims the page and highlights one element at a time with your message. "
                                + "Use task_id for a task in the guide (steps are filled in, including getting to the right page), "
                                + "or steps for anything else, with guide_id from the guide or from the current page's visible elements.",
                        schema("""
                                {"type": "object",
                                 "properties": {
                                   "task_id": {"type": ["string", "null"]},
                                   "steps": {"type": ["array", "null"], "items": {
                                     "type": "object",
                                     "properties": {
                                       "page": {"type": "string", "description": "Page ID"},
                                       "guide_id": {"type": "string"},
                                       "message": {"type": "string", "description": "One short sentence"}},
                                     "required": ["page", "guide_id", "message"],
                                     "additionalProperties": false}}},
                                 "required": ["task_id", "steps"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(GET_PREP_LIST,
                        "Get the prep list for a day from today up to 7 days ahead: portions of each dish to prepare "
                                + "(the top of its forecast range) and the total of each ingredient, from the user's recipes.",
                        schema("""
                                {"type": "object",
                                 "properties": {"date": {"type": "string", "description": "YYYY-MM-DD"}},
                                 "required": ["date"],
                                 "additionalProperties": false}
                                """)));
    }

    public List<ToolSpec> specs() {
        return specs;
    }

    // Returns the function_call_output text for the model. Problems are reported back to the
    // model (rather than thrown) so it can correct itself or ask the user.
    @Transactional(readOnly = true)
    public String execute(ToolCall call, TurnContext context) {
        try {
            JsonNode result = switch (call.name()) {
                case LIST_DISHES -> listDishes(context);
                case GET_SALES -> getSales(call.arguments(), context);
                case PROPOSE_SALES_ENTRY -> proposeSalesEntry(call.arguments(), context);
                case GET_GUIDE -> appGuide.section(call.arguments().path("id").asString())
                        .orElseGet(() -> error("No guide section " + call.arguments().path("id").asString()));
                case GUIDE_USER -> guideUser(call.arguments(), context);
                case GET_PREP_LIST -> getPrepList(call.arguments(), context);
                default -> error("Unknown tool " + call.name());
            };
            return jsonMapper.writeValueAsString(result);
        } catch (DateTimeParseException | IllegalArgumentException | InvalidForecastDateException exception) {
            return jsonMapper.writeValueAsString(error("Invalid arguments: " + exception.getMessage()));
        }
    }

    // Also used to put the dish list in the instructions, saving the model a call.
    @Transactional(readOnly = true)
    public ArrayNode listDishes(Long ownerId) {
        ArrayNode dishes = jsonMapper.createArrayNode();
        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            ObjectNode node = dishes.addObject();
            node.put("id", dish.getId());
            node.put("name", dish.getName());
            ArrayNode aliases = node.putArray("other_names");
            dishAliasRepository.findByDishIdOrderByAliasAsc(dish.getId()).forEach(alias -> aliases.add(alias.getAlias()));
        }
        return dishes;
    }

    // Dish names and aliases, used as transcription hints so the speech model spells them right.
    @Transactional(readOnly = true)
    public List<String> dishVocabulary(Long ownerId) {
        List<String> words = new ArrayList<>();
        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            words.add(dish.getName());
            dishAliasRepository.findByDishIdOrderByAliasAsc(dish.getId()).stream().map(DishAlias::getAlias).forEach(words::add);
        }
        return words;
    }

    private JsonNode listDishes(TurnContext context) {
        return listDishes(context.ownerId);
    }

    private JsonNode getSales(JsonNode arguments, TurnContext context) {
        long dishId = arguments.path("dish_id").asLong();
        LocalDate from = LocalDate.parse(arguments.path("from").asString());
        LocalDate to = LocalDate.parse(arguments.path("to").asString());

        if (dishRepository.findByIdAndOwnerId(dishId, context.ownerId).isEmpty()) {
            return error("Dish " + dishId + " not found");
        }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_SALES_DAYS) {
            return error("Use a range of 1 to " + MAX_SALES_DAYS + " days");
        }

        ArrayNode sales = jsonMapper.createArrayNode();
        dailySaleRepository.findByDishIdOrderBySaleDateAsc(dishId).stream()
                .filter(sale -> !sale.getSaleDate().isBefore(from) && !sale.getSaleDate().isAfter(to))
                .forEach(sale -> sales.addObject()
                        .put("date", sale.getSaleDate().toString())
                        .put("quantity", sale.getQuantitySold()));
        return sales;
    }

    private JsonNode proposeSalesEntry(JsonNode arguments, TurnContext context) {
        List<ProposalEntryInput> inputs = new ArrayList<>();
        for (JsonNode entry : arguments.path("entries")) {
            inputs.add(new ProposalEntryInput(
                    entry.path("dish_id").isNumber() ? entry.path("dish_id").asLong() : null,
                    textOrNull(entry.path("new_dish_name")),
                    textOrNull(entry.path("spoken_name")),
                    LocalDate.parse(entry.path("date").asString()),
                    entry.path("quantity").isNumber() ? entry.path("quantity").asInt() : null));
        }

        ProposalValidator.Result result = proposalValidator.validate(context.ownerId, inputs, context.today);
        ObjectNode output = jsonMapper.createObjectNode();

        if (!result.isValid()) {
            output.put("ok", false);
            ArrayNode problems = output.putArray("problems");
            result.problems().forEach(problems::add);
            output.put("note", "Nothing was shown to the user. Fix the entries, or ask the user what they meant.");
            return output;
        }

        context.proposal = result.entries();
        output.put("ok", true);
        output.set("entries", jsonMapper.valueToTree(result.entries()));
        output.put("note", "Shown to the user as a confirm card. Not saved until they confirm. "
                + "Mention any entry that replaces an existing quantity (previousQuantity).");
        return output;
    }

    // Only what a spoken answer needs, to keep the model's input short.
    private JsonNode getPrepList(JsonNode arguments, TurnContext context) {
        PrepListResponse prepList = prepListService.prepList(context.ownerId,
                LocalDate.parse(arguments.path("date").asString()), null);
        ObjectNode output = jsonMapper.createObjectNode();
        output.put("date", prepList.date().toString());
        if (prepList.closed() != null) {
            output.put("closed", prepList.closed());
            return output;
        }

        ArrayNode dishes = output.putArray("dishes");
        List<String> withoutRecipe = new ArrayList<>();
        for (PrepListResponse.Dish dish : prepList.dishes()) {
            dishes.addObject().put("dish", dish.dish()).put("portions", dish.portions()).put("forecast", dish.expected());
            if (!dish.hasRecipe()) withoutRecipe.add(dish.dish());
        }
        ArrayNode ingredients = output.putArray("ingredients");
        prepList.ingredients().forEach(ingredient -> ingredients.addObject()
                .put("ingredient", ingredient.ingredient())
                .put("quantity", ingredient.quantity())
                .put("unit", ingredient.unit()));
        output.set("dishes_without_recipe", jsonMapper.valueToTree(withoutRecipe));
        output.set("not_forecast", jsonMapper.valueToTree(prepList.skipped()));
        return output;
    }

    private JsonNode guideUser(JsonNode arguments, TurnContext context) {
        String taskId = textOrNull(arguments.path("task_id"));
        List<GuideStep> steps = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        if (taskId != null) {
            steps = appGuide.stepsFor(taskId, context.page);
            if (steps.isEmpty()) problems.add("No task " + taskId + ".");
        } else {
            for (JsonNode step : arguments.path("steps")) {
                String page = step.path("page").asString();
                String guideId = step.path("guide_id").asString();
                String message = step.path("message").asString().strip();
                boolean known = appGuide.hasElement(page, guideId)
                        || (page.equals(context.page) && context.liveElementIds.contains(guideId));

                if (appGuide.url(page) == null) {
                    problems.add("No page " + page + ".");
                } else if (!known) {
                    problems.add("No element " + guideId + " on page " + page + ".");
                } else if (message.isEmpty() || message.length() > MAX_STEP_MESSAGE) {
                    problems.add("Step messages must be 1 to " + MAX_STEP_MESSAGE + " characters.");
                } else {
                    steps.add(appGuide.step(page, guideId, message));
                }
            }
            if (steps.isEmpty() && problems.isEmpty()) problems.add("Give a task_id or at least one step.");
        }
        if (steps.size() > MAX_GUIDE_STEPS) problems.add("At most " + MAX_GUIDE_STEPS + " steps.");

        ObjectNode output = jsonMapper.createObjectNode();
        if (!problems.isEmpty()) {
            output.put("ok", false);
            ArrayNode list = output.putArray("problems");
            problems.forEach(list::add);
            return output;
        }

        context.guide = steps;
        output.put("ok", true);
        output.put("steps", steps.size());
        output.put("note", "The tour starts when your reply is shown. Keep the reply to one short sentence.");
        return output;
    }

    private ObjectNode error(String message) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("ok", false);
        node.put("error", message);
        return node;
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private JsonNode schema(String json) {
        return jsonMapper.readTree(json);
    }
}
