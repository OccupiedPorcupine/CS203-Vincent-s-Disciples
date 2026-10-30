package app.assistant;

import java.util.ArrayList;
import java.util.List;

// Which languages a message uses, read from its scripts: Chinese characters are "zh", Latin letters "en".
// OpenAI's own language tags change between runs for the same clip, so they aren't used.
final class MessageLanguages {

    static final String MIXED = "mixed";

    private MessageLanguages() {
    }

    // In order of first appearance.
    static List<String> in(String text) {
        List<String> languages = new ArrayList<>();
        text.codePoints().forEach(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            String language = script == Character.UnicodeScript.HAN ? "zh"
                    : script == Character.UnicodeScript.LATIN ? "en" : null;
            if (language != null && !languages.contains(language)) {
                languages.add(language);
            }
        });
        return languages;
    }

    // One label per message for analysis: "en", "zh", "mixed", or null when there are no words (e.g. "42").
    static String label(String text) {
        List<String> languages = in(text);
        if (languages.isEmpty()) {
            return null;
        }
        return languages.size() > 1 ? MIXED : languages.get(0);
    }
}
