package app.assistant;

import app.dish.Dish;
import app.dish.DishAliasRepository;
import app.dish.DishRepository;
import app.dto.ProposalEntryInput;
import app.sale.DailySaleRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Unit tests for the checks around the model: proposal validation, rate limiting and
// conversation trimming.
class AssistantGuardsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private ProposalValidator validator(Dish... dishes) {
        var dishRepository = mock(DishRepository.class);
        var saleRepository = mock(DailySaleRepository.class);
        var aliasRepository = mock(DishAliasRepository.class);
        when(dishRepository.findByIdAndOwnerId(anyLong(), anyLong())).thenReturn(Optional.empty());
        for (Dish dish : dishes) {
            when(dishRepository.findByIdAndOwnerId(dish.getId(), 7L)).thenReturn(Optional.of(dish));
        }
        when(dishRepository.findByOwnerIdOrderByNameAsc(7L)).thenReturn(List.of(dishes));
        when(aliasRepository.findByOwnerIdAndNormalizedAlias(anyLong(), any())).thenReturn(Optional.empty());
        when(saleRepository.findByDishIdAndSaleDate(anyLong(), any())).thenReturn(Optional.empty());
        return new ProposalValidator(dishRepository, aliasRepository, saleRepository);
    }

    private static Dish dish(long id, String name) {
        Dish dish = mock(Dish.class);
        when(dish.getId()).thenReturn(id);
        when(dish.getName()).thenReturn(name);
        return dish;
    }

    private static ProposalEntryInput entry(Long dishId, String newDishName, LocalDate date, Integer quantity) {
        return new ProposalEntryInput(dishId, newDishName, null, date, quantity);
    }

    @Test
    void validatorAcceptsAPlausibleEntry() {
        var result = validator(dish(3, "Chicken rice")).validate(7L, List.of(entry(3L, null, TODAY, 42)), TODAY);

        assertTrue(result.isValid());
        assertEquals("Chicken rice", result.entries().getFirst().dishName());
    }

    @Test
    void validatorRejectsFutureOldAndImplausibleEntries() {
        var validator = validator(dish(3, "Chicken rice"));

        assertFalse(validator.validate(7L, List.of(entry(3L, null, TODAY.plusDays(1), 42)), TODAY).isValid());
        assertFalse(validator.validate(7L, List.of(entry(3L, null, TODAY.minusDays(366), 42)), TODAY).isValid());
        assertFalse(validator.validate(7L, List.of(entry(3L, null, TODAY, -1)), TODAY).isValid());
        assertFalse(validator.validate(7L, List.of(entry(3L, null, TODAY, 10000)), TODAY).isValid());
        assertFalse(validator.validate(7L, List.of(entry(3L, null, TODAY, null)), TODAY).isValid());
        assertFalse(validator.validate(7L, List.of(entry(3L, null, TODAY, 1), entry(3L, null, TODAY, 2)), TODAY).isValid());
        assertFalse(validator.validate(7L, List.of(), TODAY).isValid());
    }

    @Test
    void validatorRejectsDishesTheUserDoesNotOwn() {
        var result = validator(dish(3, "Chicken rice")).validate(7L, List.of(entry(99L, null, TODAY, 5)), TODAY);

        assertEquals(List.of("Dish 99: dish not found."), result.problems());
    }

    @Test
    void aNewDishWithAnExistingNameIsTheExistingDish() {
        var result = validator(dish(3, "Chicken rice")).validate(7L, List.of(entry(null, "  CHICKEN rice ", TODAY, 5)), TODAY);

        assertTrue(result.isValid());
        assertEquals(3L, result.entries().getFirst().dishId());
        assertFalse(result.entries().getFirst().isNewDish());
    }

    @Test
    void rateLimiterAllowsTwentyRequestsAMinute() {
        var clock = Clock.fixed(Instant.parse("2026-10-29T04:00:00Z"), ZoneOffset.UTC);
        var limiter = new AssistantRateLimiter(clock);

        for (int i = 0; i < AssistantRateLimiter.MAX_REQUESTS; i++) {
            limiter.check(7L);
        }
        assertEquals("RATE_LIMITED", assertThrows(AssistantException.class, () -> limiter.check(7L)).getCode());
        limiter.check(8L); // other users are unaffected

        var later = new AssistantRateLimiter(Clock.offset(clock, AssistantRateLimiter.WINDOW.plusSeconds(1)));
        later.check(7L);
    }

    @Test
    void speechCacheKeepsTheMostRecentlyUsedEntries() {
        var cache = new SpeechCache();
        for (int i = 0; i < SpeechCache.MAX_ENTRIES; i++) {
            cache.put("text " + i, new byte[]{(byte) i});
        }
        cache.get("text 0"); // used recently, so kept

        cache.put("one more", new byte[]{1});

        assertNotNull(cache.get("text 0"));
        assertNull(cache.get("text 1"));
        assertNotNull(cache.get("one more"));
    }

    @Test
    void trimmingKeepsFunctionCallsWithTheirOutputs() {
        List<JsonNode> items = new ArrayList<>();
        for (int turn = 0; turn < 20; turn++) {
            items.add(jsonMapper.readTree("{\"role\": \"user\", \"content\": \"turn " + turn + "\"}"));
            items.add(jsonMapper.readTree("{\"type\": \"function_call\", \"call_id\": \"c" + turn + "\"}"));
            items.add(jsonMapper.readTree("{\"type\": \"function_call_output\", \"call_id\": \"c" + turn + "\"}"));
        }

        var trimmed = ConversationStore.trim(items);

        assertTrue(trimmed.size() <= ConversationStore.MAX_ITEMS);
        assertEquals("user", trimmed.getFirst().path("role").asString());
        assertEquals("turn 19", trimmed.get(trimmed.size() - 3).path("content").asString());
    }
}
