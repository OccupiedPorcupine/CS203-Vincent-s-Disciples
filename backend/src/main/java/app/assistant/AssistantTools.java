package app.assistant;

import app.assistant.AssistantModelClient.ToolCall;
import app.assistant.AssistantModelClient.ToolSpec;
import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.ProposalEntryInput;
import app.sale.DailySaleRepository;
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

// The functions the language model may call. None of them write to the database: the most the
// model can do is propose sales, which the user then confirms. The user's ID comes from the
// session (TurnContext), never from the model.
@Component
public class AssistantTools {

    static final String LIST_DISHES = "list_dishes";
    static final String GET_SALES = "get_sales";
    static final String PROPOSE_SALES_ENTRY = "propose_sales_entry";
    private static final int MAX_SALES_DAYS = 120;

    private final DishRepository dishRepository;
    private final DishAliasRepository dishAliasRepository;
    private final DailySaleRepository dailySaleRepository;
    private final ProposalValidator proposalValidator;
    private final JsonMapper jsonMapper;
    private final List<ToolSpec> specs;

    // What a turn's tool calls have produced so far. The last valid proposal in a turn is the one shown.
    public static class TurnContext {
        final Long ownerId;
        final LocalDate today;
        List<ProposedEntry> proposal;

        public TurnContext(Long ownerId, LocalDate today) {
            this.ownerId = ownerId;
            this.today = today;
        }

        public List<ProposedEntry> proposal() {
            return proposal;
        }
    }

    public AssistantTools(DishRepository dishRepository, DishAliasRepository dishAliasRepository,
                          DailySaleRepository dailySaleRepository, ProposalValidator proposalValidator,
                          JsonMapper jsonMapper) {
        this.dishRepository = dishRepository;
        this.dishAliasRepository = dishAliasRepository;
        this.dailySaleRepository = dailySaleRepository;
        this.proposalValidator = proposalValidator;
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
                default -> error("Unknown tool " + call.name());
            };
            return jsonMapper.writeValueAsString(result);
        } catch (DateTimeParseException | IllegalArgumentException exception) {
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
