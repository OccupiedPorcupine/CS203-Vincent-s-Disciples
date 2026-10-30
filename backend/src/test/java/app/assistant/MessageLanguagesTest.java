package app.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageLanguagesTest {

    @Test
    void languagesAreListedInTheOrderTheyAppear() {
        assertEquals(List.of("en", "zh"), MessageLanguages.in("Eh today 叉烧饭 30"));
        assertEquals(List.of("zh", "en"), MessageLanguages.in("今天 chicken rice 42"));
        assertEquals(List.of(), MessageLanguages.in("42, 30"));
    }

    @Test
    void eachMessageGetsOneLabel() {
        assertEquals("en", MessageLanguages.label("today chicken rice 42 lor"));
        assertEquals("zh", MessageLanguages.label("好的，今天烧鸡饭四十二份。"));
        assertEquals("mixed", MessageLanguages.label("今天 chicken rice 42，叉烧饭三十"));
        assertEquals("mixed", MessageLanguages.label("chicken rice 42, 叉烧饭 30"));
        assertNull(MessageLanguages.label("42"));
    }
}
