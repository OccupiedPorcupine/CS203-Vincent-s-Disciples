package app.assistant;

import app.assistant.AssistantInteraction.Outcome;
import app.assistant.AssistantModelClient.ModelResponse;
import app.assistant.AssistantModelClient.ToolCall;
import app.assistant.guide.GuideRetriever;
import app.assistant.guide.GuideStep;
import app.dish.Dish;
import app.dish.DishAlias;
import app.dish.DishRepository;
import app.dto.*;
import app.price.DishPriceService;
import app.price.PriceBook;
import app.service.DailySaleService;
import app.service.DishAliasService;
import app.service.StallClosureService;
import app.service.DishService;
import app.user.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

// Runs the voice assistant: transcription, the model/tool loop for each message, and
// confirming or cancelling the sales it proposes. Only confirmProposal writes sales.
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    static final Duration PROPOSAL_LIFETIME = Duration.ofMinutes(15);
    static final int MAX_MODEL_CALLS = 5;
    static final long MAX_AUDIO_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> AUDIO_EXTENSIONS = Map.of(
            "audio/webm", "webm", "video/webm", "webm", "audio/mp4", "m4a", "video/mp4", "mp4",
            "audio/x-m4a", "m4a", "audio/mpeg", "mp3", "audio/wav", "wav", "audio/x-wav", "wav");

    private final AssistantModelClient modelClient;
    private final AssistantTools tools;
    private final ConversationStore conversations;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantInteractionRepository interactions;
    private final AppUserRepository users;
    private final ProposalValidator proposalValidator;
    private final DishRepository dishRepository;
    private final DishService dishService;
    private final DailySaleService dailySaleService;
    private final DishAliasService dishAliasService;
    private final StallClosureService stallClosureService;
    private final GuideRetriever guideRetriever;
    private final SpeechCache speechCache;
    private final DishPriceService priceService;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AssistantService(AssistantModelClient modelClient, AssistantTools tools, ConversationStore conversations,
                            AssistantRateLimiter rateLimiter, AssistantInteractionRepository interactions,
                            AppUserRepository users, ProposalValidator proposalValidator, DishRepository dishRepository,
                            DishService dishService, DailySaleService dailySaleService,
                            DishAliasService dishAliasService, StallClosureService stallClosureService,
                            GuideRetriever guideRetriever,
                            SpeechCache speechCache, DishPriceService priceService, JsonMapper jsonMapper, Clock clock) {
        this.modelClient = modelClient;
        this.tools = tools;
        this.conversations = conversations;
        this.rateLimiter = rateLimiter;
        this.interactions = interactions;
        this.users = users;
        this.proposalValidator = proposalValidator;
        this.dishRepository = dishRepository;
        this.dishService = dishService;
        this.dailySaleService = dailySaleService;
        this.dishAliasService = dishAliasService;
        this.stallClosureService = stallClosureService;
        this.guideRetriever = guideRetriever;
        this.speechCache = speechCache;
        this.priceService = priceService;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    public AssistantStatusResponse status() {
        return new AssistantStatusResponse(modelClient.isAvailable(), modelClient.supportsVoice(), modelClient.modelName());
    }

    public TranscriptionResponse transcribe(Long ownerId, MultipartFile audio) {
        rateLimiter.check(ownerId);
        long started = System.nanoTime();

        if (audio == null || audio.isEmpty()) {
            log.warn("Transcribe for user {}: no audio received", ownerId);
            throw new AssistantException(HttpStatus.BAD_REQUEST, "NO_AUDIO", "No audio was recorded.");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new AssistantException(HttpStatus.BAD_REQUEST, "AUDIO_TOO_LONG", "The recording is too long. Please keep it under a minute.");
        }
        String contentType = audio.getContentType() == null ? "" : audio.getContentType().split(";")[0].strip();
        String extension = AUDIO_EXTENSIONS.get(contentType);
        if (extension == null) {
            log.warn("Transcribe for user {}: unsupported content type '{}' ({} bytes)", ownerId, audio.getContentType(), audio.getSize());
            throw new AssistantException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AUDIO", "This audio format isn't supported.");
        }

        try {
            AssistantModelClient.Transcription transcription = modelClient.transcribe(
                    audio.getBytes(), "speech." + extension, tools.dishVocabulary(ownerId));
            log.info("Transcribed for user {}: {} bytes of {} in {} ms -> {} characters, languages {}", ownerId,
                    audio.getSize(), contentType, millisSince(started), transcription.text().length(), transcription.languages());
            log.debug("Transcript for user {}: {}", ownerId, transcription.text());
            return new TranscriptionResponse(transcription.text(), transcription.languages());
        } catch (IOException exception) {
            throw new AssistantException(HttpStatus.BAD_REQUEST, "NO_AUDIO", "The recording could not be read.");
        }
    }

    // MP3 of a reply or guide step. Guide steps repeat, so audio is cached, and cached audio
    // doesn't count towards the user's rate limit.
    public byte[] speak(Long ownerId, String text) {
        String cleaned = text.strip();
        byte[] cached = speechCache.get(cleaned);
        if (cached != null) {
            log.info("Speech for user {}: {} characters from cache ({} bytes)", ownerId, cleaned.length(), cached.length);
            return cached;
        }
        rateLimiter.check(ownerId);
        long started = System.nanoTime();
        byte[] audio = modelClient.speak(cleaned);
        speechCache.put(cleaned, audio);
        log.info("Speech for user {}: {} characters -> {} bytes in {} ms", ownerId, cleaned.length(), audio.length, millisSince(started));
        return audio;
    }

    public AssistantMessageResponse handleMessage(Long ownerId, AssistantMessageRequest request) {
        rateLimiter.check(ownerId);
        if (!modelClient.isAvailable()) {
            throw AssistantException.unavailable("The assistant is not set up on this server yet.");
        }

        long started = System.nanoTime();
        String conversationId = request.conversationId() == null ? UUID.randomUUID().toString() : request.conversationId();
        LocalDate today = LocalDate.now(clock);

        List<JsonNode> items = conversations.load(ownerId, conversationId);
        ObjectNode userMessage = jsonMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", request.text().strip());
        items.add(userMessage);

        List<AssistantMessageRequest.PageElement> pageElements = request.pageContext() == null ? List.of() : request.pageContext();
        Set<String> liveElementIds = new HashSet<>();
        pageElements.forEach(element -> liveElementIds.add(element.id()));

        String instructions = AssistantPrompt.build(today, request.page(), tools.listDishes(ownerId, today).toString(),
                guideRetriever.context(request.text(), request.page()), jsonMapper.writeValueAsString(pageElements),
                tools.closures(ownerId, today).toString());
        AssistantTools.TurnContext context = new AssistantTools.TurnContext(ownerId, today, request.page(), liveElementIds);
        String reply = null;
        List<String> toolsCalled = new ArrayList<>();
        int modelCalls = 0;

        for (int call = 0; call < MAX_MODEL_CALLS && reply == null; call++) {
            modelCalls++;
            ModelResponse response = modelClient.respond(instructions, items, tools.specs());
            items.addAll(response.outputItems());

            if (response.toolCalls().isEmpty()) {
                reply = response.text();
            }
            for (ToolCall toolCall : response.toolCalls()) {
                toolsCalled.add(toolCall.name());
                ObjectNode output = jsonMapper.createObjectNode();
                output.put("type", "function_call_output");
                output.put("call_id", toolCall.callId());
                output.put("output", tools.execute(toolCall, context));
                items.add(output);
            }
        }

        List<ProposedEntry> proposal = context.proposal();
        ProposedPrice priceChange = context.priceChange();
        List<ProposedClosure> closures = context.closures();
        List<GuideStep> guide = context.guide();
        if (reply == null || reply.isBlank()) {
            reply = proposal != null || closures != null ? "Please check these and press Confirm to save them."
                    : priceChange != null ? "Please check the new price and press Confirm to save it."
                    : guide != null ? "Let me show you."
                    : "Sorry, I couldn't work that out. Please try again.";
        }
        String proposalType = proposal != null || closures != null ? "SALES" : priceChange != null ? "PRICE" : null;
        conversations.save(ownerId, conversationId, items);

        AssistantInteraction interaction = interactions.save(new AssistantInteraction(
                users.getReferenceById(ownerId),
                conversationId,
                request.page(),
                request.inputMode() == null ? "text" : request.inputMode(),
                request.text().strip(),
                MessageLanguages.label(request.text()),
                truncate(reply, 4000),
                modelClient.modelName(),
                (int) Duration.ofNanos(System.nanoTime() - started).toMillis(),
                proposalType,
                "PRICE".equals(proposalType) ? jsonMapper.writeValueAsString(priceChange)
                        : proposal == null ? null : jsonMapper.writeValueAsString(proposal),
                closures == null ? null : jsonMapper.writeValueAsString(closures),
                clock.instant()));

        log.info("Message from user {} ({}, page {}, {} characters, {}): {} model calls, tools {}, proposal {}, guide {} steps, {} ms",
                ownerId, request.inputMode() == null ? "text" : request.inputMode(), request.page(), request.text().strip().length(), interaction.getDetectedLanguages(),
                modelCalls, toolsCalled, proposalType == null ? "none"
                        : "PRICE".equals(proposalType) ? "#" + interaction.getId() + " price of dish " + priceChange.dishId()
                        : "#" + interaction.getId() + " with " + (proposal == null ? 0 : proposal.size()) + " entries and "
                                + (closures == null ? 0 : closures.size()) + " closures",
                guide == null ? 0 : guide.size(), millisSince(started));
        log.debug("Message from user {}: {} -> {}", ownerId, request.text().strip(), reply);

        boolean sales = "SALES".equals(proposalType);
        AssistantMessageResponse.Proposal proposalView = proposalType == null ? null : new AssistantMessageResponse.Proposal(
                interaction.getId(), proposalType,
                sales ? (proposal == null ? List.of() : proposal) : null,
                sales ? (closures == null ? List.of() : closures) : null,
                priceChange, interaction.getCreatedAt().plus(PROPOSAL_LIFETIME));
        return new AssistantMessageResponse(conversationId, reply, proposalView, guide);
    }

    // Saves a proposal, as proposed or as the user edited it. Confirming again returns the same
    // result without saving twice. Entries are checked again because the user may have edited them.
    // Closed-day changes can't be edited on the card; they are saved as proposed.
    @Transactional(noRollbackFor = AssistantException.class)
    public ConfirmProposalResponse confirmProposal(Long ownerId, Long proposalId, ConfirmProposalRequest request) {
        AssistantInteraction interaction = findProposal(ownerId, proposalId);

        switch (interaction.getOutcome()) {
            case CONFIRMED, EDITED -> {
                return interaction.isPriceProposal()
                        ? new ConfirmProposalResponse(interaction.getOutcome().name(), null, null, readPrice(interaction.getFinalJson()))
                        : new ConfirmProposalResponse(interaction.getOutcome().name(),
                                readEntries(interaction.getFinalJson()), readClosures(interaction.getClosuresJson()), null);
            }
            case CANCELLED, EXPIRED -> throw closed(interaction);
            default -> {
            }
        }

        Instant now = clock.instant();
        if (now.isAfter(interaction.getCreatedAt().plus(PROPOSAL_LIFETIME))) {
            interaction.resolve(Outcome.EXPIRED, null, now);
            throw closed(interaction);
        }
        if (interaction.isPriceProposal()) {
            return confirmPrice(ownerId, interaction, request, now);
        }

        List<ProposedEntry> proposed = readEntries(interaction.getProposalJson());
        List<ProposedClosure> closures = readClosures(interaction.getClosuresJson());
        List<ProposalEntryInput> inputs = request == null || request.entries() == null
                ? proposed.stream().map(AssistantService::toInput).toList()
                : request.entries();

        if (inputs.isEmpty() && closures.isEmpty()) {
            throw new AssistantException(HttpStatus.BAD_REQUEST, "INVALID_PROPOSAL", "There is nothing to save.");
        }
        // A card with only closed days has no sales to check.
        ProposalValidator.Result result = inputs.isEmpty()
                ? new ProposalValidator.Result(List.of(), List.of())
                : proposalValidator.validate(ownerId, inputs, LocalDate.now(clock));
        if (!result.isValid()) {
            throw new AssistantException(HttpStatus.BAD_REQUEST, "INVALID_PROPOSAL", String.join(" ", result.problems()));
        }

        Map<String, Dish> createdDishes = new HashMap<>();
        List<ProposedEntry> saved = new ArrayList<>();

        for (ProposedEntry entry : result.entries()) {
            Dish dish = entry.isNewDish()
                    ? createdDishes.computeIfAbsent(DishAlias.normalize(entry.newDishName()),
                            name -> dishService.createDish(entry.newDishName(), ownerId))
                    : dishRepository.findByIdAndOwnerId(entry.dishId(), ownerId).orElseThrow();

            UpdateDailySaleResponse sale = dailySaleService.setSale(dish.getId(), ownerId, entry.date(), entry.quantity());

            if (entry.spokenName() != null
                    && !DishAlias.normalize(entry.spokenName()).equals(DishAlias.normalize(dish.getName()))) {
                dishAliasService.rememberAlias(dish, entry.spokenName());
            }

            saved.add(new ProposedEntry(dish.getId(), dish.getName(), null, entry.spokenName(),
                    entry.date(), entry.quantity(), sale.previousQuantity()));
        }

        Set<LocalDate> alreadyClosed = new HashSet<>();
        stallClosureService.getClosures(ownerId).closedDates().forEach(closed -> alreadyClosed.add(closed.getClosedDate()));
        for (ProposedClosure closure : closures) {
            if (closure.closed()) {
                // "Closed tomorrow" again shouldn't wipe a reason given before, e.g. "Centre cleaning".
                if (closure.reason() != null || !alreadyClosed.contains(closure.date())) {
                    stallClosureService.closeDate(ownerId, closure.date(), closure.reason());
                }
            } else {
                stallClosureService.reopenDate(ownerId, closure.date());
            }
        }

        Outcome outcome = sameSales(proposed, result.entries()) ? Outcome.CONFIRMED : Outcome.EDITED;
        interaction.resolve(outcome, jsonMapper.writeValueAsString(saved), now);
        log.info("Proposal #{} for user {}: {}, {} entries and {} closures saved", proposalId, ownerId, outcome,
                saved.size(), closures.size());
        return new ConfirmProposalResponse(outcome.name(), saved, closures, null);
    }

    // The price as proposed or as the user edited it on the card. A cost the user didn't give
    // keeps the dish's current cost. Saved through DishPriceService, so earlier sales keep their price.
    private ConfirmProposalResponse confirmPrice(Long ownerId, AssistantInteraction interaction,
                                                 ConfirmProposalRequest request, Instant now) {
        ProposedPrice proposed = readPrice(interaction.getProposalJson());
        BigDecimal proposedCost = proposed.unitCost() != null ? proposed.unitCost()
                : priceService.priceBook(ownerId).on(proposed.dishId(), proposed.effectiveFrom()).map(PriceBook.Price::unitCost).orElse(null);
        BigDecimal price = request != null && request.price() != null ? request.price() : proposed.price();
        BigDecimal unitCost = request != null && request.unitCost() != null ? request.unitCost() : proposedCost;

        DishPriceResponse saved = priceService.setPrice(ownerId, proposed.dishId(),
                new SetDishPriceRequest(price, unitCost, proposed.effectiveFrom()));
        ProposedPrice result = new ProposedPrice(proposed.dishId(), proposed.dishName(), saved.price(), saved.unitCost(),
                saved.effectiveFrom(), proposed.currentPrice(), proposed.currentUnitCost());

        boolean asProposed = sameAmount(price, proposed.price()) && sameAmount(unitCost, proposedCost);
        Outcome outcome = asProposed ? Outcome.CONFIRMED : Outcome.EDITED;
        interaction.resolve(outcome, jsonMapper.writeValueAsString(result), now);
        log.info("Proposal #{} for user {}: {}, price of dish {} set to {} from {}",
                interaction.getId(), ownerId, outcome, proposed.dishId(), saved.price(), saved.effectiveFrom());
        return new ConfirmProposalResponse(outcome.name(), null, null, result);
    }

    // 5 and 5.00 are the same amount; null only equals null.
    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private ProposedPrice readPrice(String json) {
        return jsonMapper.readValue(json, ProposedPrice.class);
    }

    @Transactional(noRollbackFor = AssistantException.class)
    public void cancelProposal(Long ownerId, Long proposalId) {
        AssistantInteraction interaction = findProposal(ownerId, proposalId);

        switch (interaction.getOutcome()) {
            case PENDING -> {
                interaction.resolve(Outcome.CANCELLED, null, clock.instant());
                log.info("Proposal #{} for user {}: CANCELLED", proposalId, ownerId);
            }
            case CANCELLED, EXPIRED -> {
                // Already closed; cancelling again is harmless.
            }
            default -> throw closed(interaction);
        }
    }

    private AssistantInteraction findProposal(Long ownerId, Long proposalId) {
        return interactions.findByIdAndOwnerId(proposalId, ownerId)
                .filter(interaction -> interaction.getOutcome() != Outcome.NONE)
                .orElseThrow(() -> new AssistantException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Proposal not found."));
    }

    private static AssistantException closed(AssistantInteraction interaction) {
        String message = switch (interaction.getOutcome()) {
            case EXPIRED -> "This proposal has expired. Please say the sales again.";
            case CANCELLED -> "This proposal was cancelled.";
            default -> "This proposal was already saved.";
        };
        return new AssistantException(HttpStatus.CONFLICT, "PROPOSAL_CLOSED", message);
    }

    // Same dishes, dates and quantities; ignores what was recorded before and the spoken names.
    private static boolean sameSales(List<ProposedEntry> proposed, List<ProposedEntry> confirmed) {
        return salesKeys(proposed).equals(salesKeys(confirmed));
    }

    private static Set<String> salesKeys(List<ProposedEntry> entries) {
        Set<String> keys = new HashSet<>();
        entries.forEach(entry -> keys.add((entry.isNewDish() ? "new:" + DishAlias.normalize(entry.newDishName()) : "id:" + entry.dishId())
                + "@" + entry.date() + "=" + entry.quantity()));
        return keys;
    }

    private static ProposalEntryInput toInput(ProposedEntry entry) {
        return new ProposalEntryInput(entry.dishId(), entry.newDishName(), entry.spokenName(), entry.date(), entry.quantity());
    }

    // A card with only closed days has no sales (null JSON).
    private List<ProposedEntry> readEntries(String json) {
        return json == null ? List.of() : jsonMapper.readValue(json, new TypeReference<List<ProposedEntry>>() {});
    }

    private List<ProposedClosure> readClosures(String json) {
        return json == null ? List.of() : jsonMapper.readValue(json, new TypeReference<List<ProposedClosure>>() {});
    }

    private static String truncate(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length);
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
