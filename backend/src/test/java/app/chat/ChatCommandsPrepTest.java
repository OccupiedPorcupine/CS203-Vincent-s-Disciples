package app.chat;

import app.dto.ForecastResponse;
import app.dto.PrepListResponse;
import app.sale.DailySaleRepository;
import app.service.PrepListService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// /prep in a chat says what to buy when cooking changes an ingredient's weight, and which come from stock (#89).
class ChatCommandsPrepTest {

    @Test
    void prepSaysWhatToBuyAndWhatComesFromStock() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"), ZoneId.of("Asia/Singapore"));
        LocalDate tomorrow = LocalDate.of(2026, 11, 5);
        ChatLinkRepository links = mock(ChatLinkRepository.class);
        ChatLink link = mock(ChatLink.class);
        when(link.getOwnerId()).thenReturn(7L);
        when(links.findByChannelAndExternalId(ChatChannel.TELEGRAM, "42")).thenReturn(Optional.of(link));
        PrepListService prepLists = mock(PrepListService.class);
        when(prepLists.prepList(7L, tomorrow, null)).thenReturn(new PrepListResponse(tomorrow, null, null,
                List.of(new PrepListResponse.Dish(1L, "Chicken rice", 60, 55.0, 50, 62, ForecastResponse.Method.BASELINE,
                        "Average of your last 4 Thursdays", true, PrepListService.Rule.TOP_OF_RANGE, "The top of the likely range.")),
                List.of(new PrepListResponse.Ingredient("Chicken", "g", new BigDecimal("7200"), List.of("Chicken rice"),
                                new BigDecimal("70"), new BigDecimal("10285.715"), true),
                        new PrepListResponse.Ingredient("Rice", "g", new BigDecimal("12000"), List.of("Chicken rice"),
                                new BigDecimal("100"), new BigDecimal("12000"), false)),
                List.of()));

        String reply = new ChatCommands(links, mock(DailySaleRepository.class), prepLists, clock).prep(ChatChannel.TELEGRAM, "42");

        assertEquals("""
                Prep for Thu 5 Nov:
                • Chicken rice 60

                Ingredients:
                • Chicken 7200 g, buy 10286 g
                • Rice 12000 g (from stock)""", reply);
    }
}
