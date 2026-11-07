package app.assistant;

import app.assistant.AssistantModelClient.ToolCall;
import app.assistant.AssistantModelClient.ToolSpec;
import app.assistant.guide.AppGuide;
import app.assistant.guide.GuideStep;
import app.closure.Hours;
import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.ForecastResponse;
import app.dto.PrepListResponse;
import app.dto.ProposalEntryInput;
import app.document.DocumentRetriever;
import app.document.DocumentSearch;
import app.exception.InvalidForecastDateException;
import app.exception.StallClosedException;
import app.holiday.PublicHoliday;
import app.holiday.PublicHolidayRepository;
import app.price.DishPriceService;
import app.price.PriceBook;
import app.price.WeeklySummaryService;
import app.sale.DailySale;
import app.sale.DailySaleRepository;
import app.service.ForecastService;
import app.service.PrepListService;
import app.service.StallClosureService;
import app.service.StallClosureService.StallClosures;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

// The functions the language model may call. None of them write to the database: the most the
// model can do is propose sales and closed days, which the user then confirms. The user's ID comes from the
// session (TurnContext), never from the model.
@Component
public class AssistantTools {

    static final String LIST_DISHES = "list_dishes";
    static final String GET_SALES = "get_sales";
    static final String PROPOSE_SALES_ENTRY = "propose_sales_entry";
    static final String GET_GUIDE = "get_guide";
    static final String GUIDE_USER = "guide_user";
    static final String PROPOSE_PRICE_CHANGE = "propose_price_change";
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000");
    static final String PROPOSE_CLOSURE = "propose_closure";
    // Closed days can be proposed from this many days ago up to a year ahead.
    static final int MAX_CLOSURE_DAYS_BACK = 60;
    static final int MAX_CLOSURE_DAYS_AHEAD = 365;
    private static final int MAX_CLOSURES = 31;
    static final String GET_PREP_LIST = "get_prep_list";
    static final String EXPLAIN_FORECAST = "explain_forecast";
    // explain_forecast lists the dish's sales on this many earlier same weekdays, and on the days just before.
    private static final int EXPLAIN_SAME_WEEKDAYS = 4;
    private static final int EXPLAIN_RECENT_DAYS = 7;
    static final String GET_WEEKLY_SUMMARY = "get_weekly_summary";
    static final String SEARCH_DOCUMENTS = "search_documents";
    private static final int MAX_SALES_DAYS = 120;
    private static final int MAX_GUIDE_STEPS = 8;
    private static final int MAX_STEP_MESSAGE = 240;

    private final DishRepository dishRepository;
    private final DishAliasRepository dishAliasRepository;
    private final DailySaleRepository dailySaleRepository;
    private final ProposalValidator proposalValidator;
    private final DishPriceService priceService;
    private final StallClosureService stallClosureService;
    private final PrepListService prepListService;
    private final ForecastService forecastService;
    private final WeeklySummaryService weeklySummaryService;
    private final PublicHolidayRepository holidayRepository;
    private final AppGuide appGuide;
    private final DocumentRetriever documentRetriever;
    private final JsonMapper jsonMapper;
    private final List<ToolSpec> specs;

    // What a turn's tool calls have produced so far. The last valid proposal and guide in a turn
    // are the ones shown; a proposal is either sales or a price change, never both. liveElementIds are elements on the user's current page that the app
    // guide doesn't describe yet, which guide_user may also point at.
    public static class TurnContext {
        final Long ownerId;
        final LocalDate today;
        final String page;
        final Set<String> liveElementIds;
        List<ProposedEntry> proposal;
        ProposedPrice priceChange;
        List<ProposedClosure> closures;
        List<GuideStep> guide;
        // Document chunks found this turn, with the message or by search_documents (issue #153).
        final List<RetrievedChunk> documents = new ArrayList<>();

        public TurnContext(Long ownerId, LocalDate today, String page, Set<String> liveElementIds) {
            this.ownerId = ownerId;
            this.today = today;
            this.page = page;
            this.liveElementIds = liveElementIds;
        }

        public List<ProposedEntry> proposal() {
            return proposal;
        }

        public ProposedPrice priceChange() {
            return priceChange;
        }

        public List<ProposedClosure> closures() {
            return closures;
        }

        public List<GuideStep> guide() {
            return guide;
        }

        public List<RetrievedChunk> documents() {
            return documents;
        }

        public void addDocuments(List<DocumentSearch.Hit> hits, String via) {
            hits.forEach(hit -> documents.add(new RetrievedChunk(hit, via)));
        }
    }

    // via: "message" for chunks retrieved with the user's message, "tool" for search_documents.
    public record RetrievedChunk(DocumentSearch.Hit hit, String via) {}

    public AssistantTools(DishRepository dishRepository, DishAliasRepository dishAliasRepository,
                          DailySaleRepository dailySaleRepository, ProposalValidator proposalValidator,
                          DishPriceService priceService, StallClosureService stallClosureService,
                          PrepListService prepListService, ForecastService forecastService,
                          WeeklySummaryService weeklySummaryService, PublicHolidayRepository holidayRepository, AppGuide appGuide,
                          DocumentRetriever documentRetriever, JsonMapper jsonMapper) {
        this.dishRepository = dishRepository;
        this.dishAliasRepository = dishAliasRepository;
        this.dailySaleRepository = dailySaleRepository;
        this.proposalValidator = proposalValidator;
        this.priceService = priceService;
        this.stallClosureService = stallClosureService;
        this.prepListService = prepListService;
        this.forecastService = forecastService;
        this.weeklySummaryService = weeklySummaryService;
        this.holidayRepository = holidayRepository;
        this.appGuide = appGuide;
        this.documentRetriever = documentRetriever;
        this.jsonMapper = jsonMapper;
        this.specs = List.of(
                new ToolSpec(LIST_DISHES,
                        "List the user's dishes with their IDs, the other names the user calls them, and today's "
                                + "price and unit cost in S$ (null if not set).",
                        schema("""
                                {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
                                """)),
                new ToolSpec(GET_SALES,
                        "Get recorded daily sales for one of the user's dishes between two dates (inclusive, at most "
                                + MAX_SALES_DAYS + " days), with the time it sold out and portions left over if recorded.",
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
                        "Show the user a confirm card with the daily sales you understood from their message, "
                                + "including when a dish sold out and portions left over. "
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
                                       "quantity": {"type": ["integer", "null"], "description": "Portions sold; null only if the user gave just a sold-out time or leftovers"},
                                       "sold_out_at": {"type": ["string", "null"], "description": "HH:MM, 24-hour, when the dish sold out; null if not mentioned"},
                                       "leftover": {"type": ["integer", "null"], "description": "Portions left over (not sold); null if not mentioned"}},
                                     "required": ["dish_id", "new_dish_name", "spoken_name", "date", "quantity", "sold_out_at", "leftover"],
                                     "additionalProperties": false}}},
                                 "required": ["entries"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(PROPOSE_PRICE_CHANGE,
                        "Show the user a confirm card with a new price (and optionally a new unit cost) for one of "
                                + "their existing dishes. This does NOT change anything; the user confirms the card. "
                                + "Amounts are S$ per portion. effective_from is the first day of the new price "
                                + "(null for today); earlier sales keep their old price.",
                        schema("""
                                {"type": "object",
                                 "properties": {
                                   "dish_id": {"type": "integer"},
                                   "price": {"type": "number", "description": "New selling price per portion in S$"},
                                   "unit_cost": {"type": ["number", "null"], "description": "New cost per portion in S$; null to keep the current cost"},
                                   "effective_from": {"type": ["string", "null"], "description": "YYYY-MM-DD, or null for today"}},
                                 "required": ["dish_id", "price", "unit_cost", "effective_from"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(PROPOSE_CLOSURE,
                        "Show the user a confirm card that marks specific days as closed (the stall doesn't trade, "
                                + "e.g. a holiday or hawker centre cleaning) or open again. This does NOT save anything; "
                                + "the user confirms. Not for weekly rest days (\"closed every Tuesday\"): guide the user "
                                + "with task set-opening-days instead. If the message also has sales, call "
                                + "propose_sales_entry as well; both go on one card.",
                        schema("""
                                {"type": "object",
                                 "properties": {
                                   "closures": {"type": "array", "items": {
                                     "type": "object",
                                     "properties": {
                                       "date": {"type": "string", "description": "YYYY-MM-DD"},
                                       "closed": {"type": "boolean", "description": "true to mark closed, false to open again"},
                                       "reason": {"type": ["string", "null"], "description": "Why, if the user said, in a few words"}},
                                     "required": ["date", "closed", "reason"],
                                     "additionalProperties": false}}},
                                 "required": ["closures"],
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
                                + "(enough for the share of days its margin makes worthwhile, or, when it has no price and cost, the forecast (from the model) or the top of its forecast range (from the baseline); why says which) and the total of each ingredient, from the user's recipes: quantity is cooked, to_buy is the raw amount by the ingredient's yield, "
                                + "and perishable false means it comes from stock rather than being bought for the day.",
                        schema("""
                                {"type": "object",
                                 "properties": {"date": {"type": "string", "description": "YYYY-MM-DD"}},
                                 "required": ["date"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(EXPLAIN_FORECAST,
                        "Get one dish's forecast for a day (today up to 7 days ahead) with everything that went into it: "
                                + "how it was worked out, the past sales it used, and whether a public holiday was used. "
                                + "Use it to answer why a forecast is what it is.",
                        schema("""
                                {"type": "object",
                                 "properties": {
                                   "dish_id": {"type": "integer"},
                                   "date": {"type": "string", "description": "YYYY-MM-DD"}},
                                 "required": ["dish_id", "date"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(SEARCH_DOCUMENTS,
                        "Search the owner's own documents (suppliers, contacts, routines, house rules) with your own "
                                + "wording, when the document parts in your instructions don't answer the question. Returns "
                                + "the best matching parts as {tag, title, text}; cite a part by its tag, like [doc:12]. "
                                + "Not for sales, prices, closures or forecasts.",
                        schema("""
                                {"type": "object",
                                 "properties": {"query": {"type": "string", "description": "What to look for, in a few words"}},
                                 "required": ["query"],
                                 "additionalProperties": false}
                                """)),
                new ToolSpec(GET_WEEKLY_SUMMARY,
                        "Get the summary of the user's latest 7 days of sales, as on the dashboard: portions against the "
                                + "week before, revenue, the best-selling dish, leftovers and sell-outs, with the summary "
                                + "already written in English (text.en) and Chinese (text.zh).",
                        schema("""
                                {"type": "object", "properties": {}, "required": [], "additionalProperties": false}
                                """)));
    }

    // A chat app (page "chat") has nothing to highlight, so it doesn't get guide_user. A stall
    // without documents doesn't get search_documents, so nothing changes for it.
    public List<ToolSpec> specs(String page, boolean hasDocuments) {
        return specs.stream()
                .filter(spec -> !(AssistantService.CHAT_PAGE.equals(page) && GUIDE_USER.equals(spec.name())))
                .filter(spec -> hasDocuments || !SEARCH_DOCUMENTS.equals(spec.name()))
                .toList();
    }

    // search_documents, kept out of execute's transaction so no database connection waits on the
    // embedding call. Searches only the signed-in owner's documents.
    public String searchDocuments(ToolCall call, TurnContext context) {
        String query = call.arguments().path("query").asString("").strip();
        if (query.isEmpty()) {
            return jsonMapper.writeValueAsString(error("Give a query."));
        }
        List<DocumentSearch.Hit> hits = documentRetriever.retrieve(context.ownerId, query);
        context.addDocuments(hits, "tool");
        ObjectNode output = jsonMapper.createObjectNode();
        output.set("results", jsonMapper.readTree(documentRetriever.json(hits)));
        if (hits.isEmpty()) {
            output.put("note", "Nothing in the owner's documents matches. Say the documents don't cover it.");
        }
        return jsonMapper.writeValueAsString(output);
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
                case PROPOSE_PRICE_CHANGE -> proposePriceChange(call.arguments(), context);
                case PROPOSE_CLOSURE -> proposeClosure(call.arguments(), context);
                case GET_GUIDE -> appGuide.section(call.arguments().path("id").asString())
                        .orElseGet(() -> error("No guide section " + call.arguments().path("id").asString()));
                case GUIDE_USER -> guideUser(call.arguments(), context);
                case GET_PREP_LIST -> getPrepList(call.arguments(), context);
                case EXPLAIN_FORECAST -> explainForecast(call.arguments(), context);
                case GET_WEEKLY_SUMMARY -> jsonMapper.valueToTree(weeklySummaryService.weekly(context.ownerId));
                default -> error("Unknown tool " + call.name());
            };
            return jsonMapper.writeValueAsString(result);
        } catch (DateTimeParseException | IllegalArgumentException | InvalidForecastDateException exception) {
            return jsonMapper.writeValueAsString(error("Invalid arguments: " + exception.getMessage()));
        }
    }

    // Also used to put the dish list in the instructions, saving the model a call.
    @Transactional(readOnly = true)
    public ArrayNode listDishes(Long ownerId, LocalDate today) {
        PriceBook prices = priceService.priceBook(ownerId);
        ArrayNode dishes = jsonMapper.createArrayNode();
        for (Dish dish : dishRepository.findByOwnerIdOrderByNameAsc(ownerId)) {
            ObjectNode node = dishes.addObject();
            node.put("id", dish.getId());
            node.put("name", dish.getName());
            ArrayNode aliases = node.putArray("other_names");
            dishAliasRepository.findByDishIdOrderByAliasAsc(dish.getId()).forEach(alias -> aliases.add(alias.getAlias()));
            PriceBook.Price price = prices.on(dish.getId(), today).orElse(null);
            node.put("price", price == null ? null : price.price());
            node.put("unit_cost", price == null ? null : price.unitCost());
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
        return listDishes(context.ownerId, context.today);
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
                        .put("quantity", sale.getQuantitySold())
                        .put("sold_out_at", sale.getSoldOutAt() == null ? null : sale.getSoldOutAt().toString())
                        .put("leftover", sale.getLeftoverQuantity()));
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
                    entry.path("quantity").isNumber() ? entry.path("quantity").asInt() : null,
                    textOrNull(entry.path("sold_out_at")) == null ? null : LocalTime.parse(entry.path("sold_out_at").asString().strip()),
                    entry.path("leftover").isNumber() ? entry.path("leftover").asInt() : null));
        }

        ProposalValidator.Result result = proposalValidator.validateProposal(context.ownerId, inputs, context.today);
        ObjectNode output = jsonMapper.createObjectNode();

        // "Sold out at one" is 13:00 at a lunch stall; a time outside the stall's hours is likely a slip.
        Hours hours = stallClosureService.hours(context.ownerId).orElse(null);
        List<String> outsideHours = new ArrayList<>();
        for (int i = 0; hours != null && result.isValid() && i < inputs.size(); i++) {
            LocalTime soldOutAt = inputs.get(i).soldOutAt();
            if (soldOutAt != null && hours.minutesUntil(soldOutAt).isEmpty()) {
                outsideHours.add(result.entries().get(i).dishName() + ": sold out at " + soldOutAt + " is outside the stall's "
                        + "trading hours (" + hours + "). Use 24-hour time, e.g. 13:00 for 1 pm, or ask the user.");
            }
        }
        if (!outsideHours.isEmpty()) result = new ProposalValidator.Result(List.of(), outsideHours);

        if (!result.isValid()) {
            output.put("ok", false);
            ArrayNode problems = output.putArray("problems");
            result.problems().forEach(problems::add);
            output.put("note", "Nothing was shown to the user. Fix the entries, or ask the user what they meant.");
            return output;
        }

        context.proposal = result.entries();
        context.priceChange = null;
        output.put("ok", true);
        output.set("entries", jsonMapper.valueToTree(result.entries()));
        output.put("note", "Shown to the user as a confirm card. Not saved until they confirm. "
                + "Mention any entry that replaces an existing quantity (previousQuantity), and any sold-out time or leftovers.");
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
            dishes.addObject().put("dish", dish.dish()).put("portions", dish.portions()).put("forecast", dish.expected()).put("why", dish.why());
            if (!dish.hasRecipe()) withoutRecipe.add(dish.dish());
        }
        ArrayNode ingredients = output.putArray("ingredients");
        prepList.ingredients().forEach(ingredient -> ingredients.addObject()
                .put("ingredient", ingredient.ingredient())
                .put("quantity", ingredient.quantity())
                .put("unit", ingredient.unit())
                .put("to_buy", ingredient.toBuy())
                .put("perishable", ingredient.perishable()));
        output.set("dishes_without_recipe", jsonMapper.valueToTree(withoutRecipe));
        output.set("not_forecast", jsonMapper.valueToTree(prepList.skipped()));
        return output;
    }

    // The forecast the user sees, and only the facts it was made from, so the model has nothing to
    // explain it with but the real inputs. The model can't attribute its number to each input, so for
    // a MODEL forecast the output lists what was used, not how much each one counted.
    private JsonNode explainForecast(JsonNode arguments, TurnContext context) {
        long dishId = arguments.path("dish_id").asLong();
        LocalDate date = LocalDate.parse(arguments.path("date").asString());
        if (dishRepository.findByIdAndOwnerId(dishId, context.ownerId).isEmpty()) {
            return error("Dish " + dishId + " not found");
        }

        ForecastResponse forecast;
        try {
            forecast = forecastService.forecast(dishId, date, context.ownerId);
        } catch (StallClosedException exception) {
            return jsonMapper.createObjectNode().put("closed", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return error(exception.getMessage());
        }
        boolean usedModel = forecast.method() == ForecastResponse.Method.MODEL;

        ObjectNode output = jsonMapper.createObjectNode();
        output.put("dish", forecast.dish());
        output.put("date", date.toString());
        output.put("weekday", date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        output.put("forecast", Math.round(forecast.predictedDemand()));
        output.put("likely_low", forecast.low());
        output.put("likely_high", forecast.high());
        // How the range was worked out, in words: by the model (ForecastService) or BaselineForecaster.range.
        output.put("range_basis", usedModel
                ? "The model's own range: it expects sales to be below the low end on about 1 day in 10 like this "
                        + "one, and above the high end on about 1 in 10. A dish whose sales swing more, or a date further "
                        + "from the last recorded sales, has a wider range."
                : forecast.roughRange()
                ? "Too few past days to measure how far forecasts are usually off, so the range is a rough 25% either "
                        + "side. It narrows as more sales are recorded."
                : "How far an average of past sales was off on recent days (the middle 80% of those misses), "
                        + "when worked out as many days ahead as this one, applied to this forecast. A dish whose sales "
                        + "swing more, or a date further from the last recorded sales, has a wider range.");
        output.put("method", usedModel
                ? "the forecast model (Chronos-2, a pretrained model that forecasts from the dish's own daily sales)"
                : "an average of the dish's own past sales");
        output.put("basis", forecast.basis());
        output.put("why_not_model", forecast.note());

        List<DailySale> before = dailySaleRepository.findByDishIdOrderBySaleDateAsc(dishId).stream()
                .filter(sale -> sale.getSaleDate().isBefore(date))
                .toList();
        output.set("same_weekday_sales", sales(before, sale -> sale.getSaleDate().getDayOfWeek() == date.getDayOfWeek(),
                EXPLAIN_SAME_WEEKDAYS));
        output.set("last_7_days_sales", sales(before, sale -> !sale.getSaleDate().isBefore(date.minusDays(EXPLAIN_RECENT_DAYS)),
                EXPLAIN_RECENT_DAYS));

        // Only the model takes holidays into account; the baseline is sales only. Neither uses the weather.
        String holiday = holidayRepository.findByDate(date).map(PublicHoliday::getName).orElse(null);
        output.put("public_holiday", holiday);
        output.put("public_holiday_used", usedModel && holiday != null);
        output.put("note", "These are the only inputs. Explain the forecast from them alone, and say any other "
                + "reason the user suggests isn't taken into account.");
        return output;
    }

    // The latest few of the sales that match, oldest first.
    private ArrayNode sales(List<DailySale> sales, Predicate<DailySale> filter, int limit) {
        List<DailySale> matching = sales.stream().filter(filter).toList();
        ArrayNode list = jsonMapper.createArrayNode();
        matching.subList(Math.max(0, matching.size() - limit), matching.size()).forEach(sale -> list.addObject()
                .put("date", sale.getSaleDate().toString())
                .put("weekday", sale.getSaleDate().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH))
                .put("quantity", sale.getQuantitySold())
                .put("sold_out_at", sale.getSoldOutAt() == null ? null : sale.getSoldOutAt().toString())
                .put("leftover", sale.getLeftoverQuantity()));
        return list;
    }

    private JsonNode proposePriceChange(JsonNode arguments, TurnContext context) {
        long dishId = arguments.path("dish_id").asLong();
        Dish dish = dishRepository.findByIdAndOwnerId(dishId, context.ownerId).orElse(null);
        if (dish == null) {
            return error("Dish " + dishId + " not found. Prices can only be set for existing dishes.");
        }
        BigDecimal price = amount(arguments.path("price"));
        BigDecimal unitCost = arguments.path("unit_cost").isNumber() ? amount(arguments.path("unit_cost")) : null;
        if (price == null || (arguments.path("unit_cost").isNumber() && unitCost == null)) {
            return error("Amounts must be from 0 to " + MAX_PRICE + " S$ with at most 2 decimals.");
        }
        String from = textOrNull(arguments.path("effective_from"));
        LocalDate effectiveFrom = from == null ? context.today : LocalDate.parse(from);
        if (effectiveFrom.isBefore(context.today.minusYears(1)) || effectiveFrom.isAfter(context.today.plusYears(1))) {
            return error("effective_from must be within a year of today.");
        }

        PriceBook.Price current = priceService.priceBook(context.ownerId).on(dishId, context.today).orElse(null);
        BigDecimal currentPrice = current == null ? null : current.price();
        BigDecimal currentCost = current == null ? null : current.unitCost();
        if (currentPrice != null && currentPrice.compareTo(price) == 0
                && (unitCost == null || (currentCost != null && currentCost.compareTo(unitCost) == 0))
                && effectiveFrom.equals(context.today)) {
            return error(dish.getName() + " is already " + price + ". Nothing to change.");
        }

        context.priceChange = new ProposedPrice(dishId, dish.getName(), price, unitCost, effectiveFrom, currentPrice, currentCost);
        // A price change has its own card, so it replaces any sales or closed days from this turn.
        context.proposal = null;
        context.closures = null;
        ObjectNode output = jsonMapper.createObjectNode();
        output.put("ok", true);
        output.set("price_change", jsonMapper.valueToTree(context.priceChange));
        output.put("note", "Shown to the user as a confirm card. Nothing changes until they confirm. "
                + "Only one card shows at a time, so handle one price change per message.");
        return output;
    }

    // An amount in S$ from 0 to MAX_PRICE with at most 2 decimals, or null.
    private static BigDecimal amount(JsonNode node) {
        if (!node.isNumber()) return null;
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.signum() < 0 || value.compareTo(MAX_PRICE) > 0 || value.scale() > 2) return null;
        return value.setScale(2);
    }

    private JsonNode proposeClosure(JsonNode arguments, TurnContext context) {
        StallClosures current = stallClosureService.getClosures(context.ownerId);
        Set<LocalDate> closedDates = new HashSet<>();
        current.closedDates().forEach(closedDate -> closedDates.add(closedDate.date()));

        List<ProposedClosure> closures = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();

        for (JsonNode item : arguments.path("closures")) {
            LocalDate date = LocalDate.parse(item.path("date").asString());
            boolean closed = item.path("closed").asBoolean();
            String reason = textOrNull(item.path("reason"));
            String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            if (!seen.add(date)) {
                problems.add(date + " is listed twice.");
            } else if (date.isBefore(context.today.minusDays(MAX_CLOSURE_DAYS_BACK))
                    || date.isAfter(context.today.plusDays(MAX_CLOSURE_DAYS_AHEAD))) {
                problems.add(date + " is out of range: use a date from " + MAX_CLOSURE_DAYS_BACK
                        + " days ago to a year ahead.");
            } else if (current.restDays().contains(date.getDayOfWeek())) {
                problems.add(date + " is a " + weekday + ", a weekly rest day. Rest days are changed on the Data page "
                        + "(task set-opening-days), not here.");
            } else if (!closed && !closedDates.contains(date)) {
                problems.add(date + " isn't marked as closed, so there is nothing to open again.");
            } else if (reason != null && reason.strip().length() > 100) {
                problems.add("Keep the reason for " + date + " under 100 characters.");
            } else {
                closures.add(new ProposedClosure(date, closed, closed && reason != null ? reason.strip() : null));
            }
        }
        if (closures.isEmpty() && problems.isEmpty()) problems.add("Give at least one date.");
        if (closures.size() > MAX_CLOSURES) problems.add("At most " + MAX_CLOSURES + " days at a time.");

        ObjectNode output = jsonMapper.createObjectNode();
        if (!problems.isEmpty()) {
            output.put("ok", false);
            ArrayNode list = output.putArray("problems");
            problems.forEach(list::add);
            output.put("note", "Nothing was shown to the user. Fix the dates, or ask the user what they meant.");
            return output;
        }

        context.closures = closures;
        context.priceChange = null;
        output.put("ok", true);
        output.set("closures", jsonMapper.valueToTree(closures));
        output.put("note", "Shown to the user on the confirm card. Not saved until they confirm.");
        return output;
    }

    // The stall's weekly rest days and closed days from today on, for the instructions. NEA's
    // closures go in as ranges: a months-long renovation would be a long list of days.
    @Transactional(readOnly = true)
    public ObjectNode closures(Long ownerId, LocalDate today) {
        StallClosures current = stallClosureService.getClosures(ownerId);
        ObjectNode node = jsonMapper.createObjectNode();
        ArrayNode restDays = node.putArray("rest_days");
        current.restDays().stream().sorted().forEach(day -> restDays.add(day.name()));
        ArrayNode closedDates = node.putArray("closed_dates");
        current.closedDates().stream()
                .filter(closedDate -> !closedDate.fromNea() && !closedDate.date().isBefore(today))
                .forEach(closedDate -> closedDates.addObject()
                        .put("date", closedDate.date().toString())
                        .put("reason", closedDate.reason()));
        if (current.hawkerCentre() != null) {
            node.put("hawker_centre", current.hawkerCentre().name());
            ArrayNode nea = node.putArray("nea_closures");
            current.neaClosures().forEach(closure -> {
                ObjectNode item = nea.addObject()
                        .put("from", closure.startDate().toString())
                        .put("to", closure.endDate().toString())
                        .put("reason", closure.reason());
                if (!closure.openDays().isEmpty()) {
                    ArrayNode open = item.putArray("open_anyway");
                    closure.openDays().forEach(day -> open.add(day.toString()));
                }
            });
        }
        return node;
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
