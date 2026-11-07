package app.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Splits a document into chunks of up to about MAX_TOKENS for embedding (issue #152). Headings
// ("# ..." lines, which .docx headings become) and blank lines are the preferred places to split;
// a paragraph too long for one chunk is split between lines, then sentences, and only a single
// sentence longer than a whole chunk is ever cut mid-way. A chunk that continues a section starts
// with the section's heading and the last sentence or two of the chunk before, so it keeps its context.
final class DocumentChunker {

    static final int MAX_TOKENS = 450;
    // A heading starts a new chunk once the current one has at least this much in it; smaller
    // sections (a supplier with two lines) share a chunk with the next.
    static final int MIN_TOKENS_BEFORE_HEADING = 120;
    static final int OVERLAP_TOKENS = 60;

    private static final Pattern HEADING = Pattern.compile("#{1,6}\\s+\\S.*");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|(?<=[。！？；])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    // A piece of text to add, and whether it starts a new paragraph (joined with a blank line) or
    // continues the previous piece's paragraph (joined with a space, or a newline for a line).
    private record Piece(String text, String separator) {}

    private DocumentChunker() {
    }

    // The text that is embedded for a chunk: the document's title first, so a chunk like
    // "Order by 9pm" still matches a question about "Ah Huat Poultry".
    static String embeddedText(String title, String chunk) {
        return "Document: " + title + "\n\n" + chunk;
    }

    static List<String> chunk(String body) {
        List<String> chunks = new ArrayList<>();
        Builder current = new Builder();
        String heading = null;

        for (String block : BLANK_LINES.split(body.strip())) {
            String paragraph = block.strip();
            if (paragraph.isEmpty()) continue;

            String firstLine = paragraph.lines().findFirst().orElse("");
            if (HEADING.matcher(firstLine).matches()) {
                if (current.tokens >= MIN_TOKENS_BEFORE_HEADING) {
                    current.emitTo(chunks);
                    current = new Builder();
                }
                heading = firstLine.strip();
                // Context rather than content, so a heading is never a chunk on its own.
                current.addContext(heading);
                paragraph = paragraph.substring(firstLine.length()).strip();
                if (paragraph.isEmpty()) continue;
            }

            for (Piece piece : pieces(paragraph)) {
                if (current.tokens > 0 && current.tokens + tokens(piece.text()) > MAX_TOKENS && !current.onlyContext()) {
                    String overlap = overlap(current.text());
                    current.emitTo(chunks);
                    current = new Builder();
                    if (heading != null) current.addContext(heading);
                    if (!overlap.isEmpty()) current.addContext(overlap);
                }
                current.add(piece);
            }
        }
        current.emitTo(chunks);
        return chunks;
    }

    // The paragraph whole if it fits; otherwise its lines, its sentences, or (for a sentence longer
    // than a chunk) runs of words.
    private static List<Piece> pieces(String paragraph) {
        List<Piece> pieces = new ArrayList<>();
        if (tokens(paragraph) <= MAX_TOKENS) {
            pieces.add(new Piece(paragraph, "\n\n"));
            return pieces;
        }
        List<String> lines = paragraph.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        boolean byLine = lines.size() > 1;
        List<String> parts = byLine ? lines : List.of(SENTENCE_END.split(paragraph));
        String separator = "\n\n";
        for (String part : parts) {
            String text = part.strip();
            if (text.isEmpty()) continue;
            List<String> fitted = tokens(text) <= MAX_TOKENS ? List.of(text)
                    : byLine ? sentencesOrWords(text) : words(text);
            for (String piece : fitted) {
                pieces.add(new Piece(piece, separator));
                // Chinese sentences follow each other without a space.
                separator = byLine ? "\n" : piece.matches(".*[。！？；]") ? "" : " ";
            }
        }
        return pieces;
    }

    private static List<String> sentencesOrWords(String line) {
        List<String> result = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(line)) {
            String text = sentence.strip();
            if (text.isEmpty()) continue;
            if (tokens(text) <= MAX_TOKENS) result.add(text);
            else result.addAll(words(text));
        }
        return result;
    }

    // Runs of words that fit in a chunk; a "word" longer than a chunk (a long link) is cut.
    private static List<String> words(String text) {
        List<String> runs = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (String word : WHITESPACE.split(text)) {
            while (tokens(word) > MAX_TOKENS) {
                int cut = Math.min(word.length(), MAX_TOKENS);
                if (!run.isEmpty()) {
                    runs.add(run.toString());
                    run.setLength(0);
                }
                runs.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            if (!run.isEmpty() && tokens(run + " " + word) > MAX_TOKENS) {
                runs.add(run.toString());
                run.setLength(0);
            }
            if (!word.isEmpty()) run.append(run.isEmpty() ? "" : " ").append(word);
        }
        if (!run.isEmpty()) runs.add(run.toString());
        return runs;
    }

    // The last sentence or two of a chunk, up to OVERLAP_TOKENS; empty if the last sentence alone is longer.
    private static String overlap(String text) {
        String[] sentences = SENTENCE_END.split(text.substring(text.lastIndexOf('\n') + 1));
        String overlap = "";
        for (int i = sentences.length - 1; i >= 0; i--) {
            String candidate = (sentences[i].strip() + " " + overlap).strip();
            if (tokens(candidate) > OVERLAP_TOKENS) break;
            overlap = candidate;
        }
        return overlap;
    }

    // About how many tokens the text is for an OpenAI model: one per Chinese, Japanese or Korean
    // character, and one per four other characters.
    static int tokens(String text) {
        int wide = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL) {
                wide++;
            } else {
                other++;
            }
            i += Character.charCount(codePoint);
        }
        return wide + (other + 3) / 4;
    }

    private static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private int tokens;
        private boolean hasContent;

        // The heading or overlap carried over from the chunk before.
        void addContext(String context) {
            append(context, "\n\n");
        }

        void add(Piece piece) {
            append(piece.text(), piece.separator());
            hasContent = true;
        }

        boolean onlyContext() {
            return !hasContent;
        }

        private void append(String piece, String separator) {
            if (!text.isEmpty()) text.append(separator);
            text.append(piece);
            tokens = DocumentChunker.tokens(text.toString());
        }

        String text() {
            return text.toString();
        }

        void emitTo(List<String> chunks) {
            if (hasContent) chunks.add(text.toString().strip());
        }
    }
}
