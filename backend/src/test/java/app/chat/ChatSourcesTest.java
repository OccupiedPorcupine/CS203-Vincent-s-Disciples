package app.chat;

import app.dto.DocumentSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Chat apps can't show the web's source chips, so a reply names its documents (issue #153).
class ChatSourcesTest {

    @Test
    void sourcesFollowTheReply() {
        assertEquals("Order by 9pm. (from: Ah Huat Poultry, Suppliers)", ChatMessageService.withSources("Order by 9pm.",
                List.of(new DocumentSource(1L, "Ah Huat Poultry"), new DocumentSource(2L, "Suppliers"))));
        assertEquals("Saved.", ChatMessageService.withSources("Saved.", List.of()));
    }
}
