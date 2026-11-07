package app.document;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

// A deterministic stand-in for an embedding model (ASSISTANT_PROVIDER=offline), so tests and CI
// run without an API key. Not semantic: each word (and each Chinese character) is hashed into one
// of the vector's dimensions and counted, so texts that share words point the same way.
public class OfflineEmbeddingClient implements EmbeddingClient {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    // Words too common to say what a text is about.
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "you", "your", "are", "was", "were", "with", "from", "this", "that", "what", "when",
            "who", "how", "where", "which", "why", "can", "does", "did", "have", "has", "not", "but", "all", "any",
            "our", "their", "they", "them", "there", "then", "than", "into", "about", "will", "would", "should",
            "must", "its", "his", "her", "one", "get", "got", "need", "want", "tell", "say", "said", "also", "just");

    private final int dimensions;

    public OfflineEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public String modelName() {
        return "offline-hashed-words";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    // Unrelated texts share almost no words, so anything sharing a couple of words passes.
    @Override
    public double minSimilarity() {
        return 0.1;
    }

    @Override
    public Embeddings embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) vectors.add(embed(text));
        return new Embeddings(vectors, 0);
    }

    private float[] embed(String text) {
        float[] vector = new float[dimensions];
        Matcher word = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (word.find()) {
            String token = word.group();
            if (HAN.matcher(token).find()) {
                // Chinese has no spaces: count each character.
                token.codePoints().forEach(codePoint -> vector[bucket(new String(Character.toChars(codePoint)))]++);
            } else if (token.length() > 2 && !STOP_WORDS.contains(token)) {
                vector[bucket(token.replaceAll("(?<=...)(s|es|ing|ed)$", ""))]++;
            }
        }
        return vector;
    }

    private int bucket(String token) {
        CRC32 crc = new CRC32();
        crc.update(token.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % dimensions);
    }
}
