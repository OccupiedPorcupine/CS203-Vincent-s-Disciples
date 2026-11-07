package app.document;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkerTest {

    @Test
    void aShortDocumentIsOneChunk() {
        assertEquals(List.of("Order by 9pm.\n\nCall 9123 4567."), DocumentChunker.chunk("Order by 9pm.\n\nCall 9123 4567."));
    }

    @Test
    void theTitleLeadsTheEmbeddedText() {
        assertEquals("Document: Ah Huat Poultry\n\nOrder by 9pm.", DocumentChunker.embeddedText("Ah Huat Poultry", "Order by 9pm."));
    }

    @Test
    void longSectionsSplitAtHeadingsAndEachChunkStaysUnderTheLimit() {
        String body = section("Poultry", "Ah Huat delivers chicken every morning.", 30)
                + "\n\n" + section("Eggs", "Kim Lee brings eggs on Mondays and Thursdays.", 30);

        List<String> chunks = DocumentChunker.chunk(body);

        assertTrue(chunks.size() >= 2, chunks.toString());
        chunks.forEach(chunk -> assertTrue(DocumentChunker.tokens(chunk) <= DocumentChunker.MAX_TOKENS + DocumentChunker.OVERLAP_TOKENS + 10,
                "Too long: " + DocumentChunker.tokens(chunk)));
        assertTrue(chunks.getFirst().startsWith("# Poultry"));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.startsWith("# Eggs")), "The Eggs section starts its own chunk");
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.contains("Ah Huat") && chunk.contains("Kim Lee")),
                "Sections that each fill a chunk aren't mixed");
    }

    @Test
    void smallSectionsShareAChunk() {
        String body = "# Poultry\n\nAh Huat, 9123 4567, order by 9pm.\n\n# Eggs\n\nKim Lee, 9765 4321, order by 6pm.";

        assertEquals(List.of(body), DocumentChunker.chunk(body));
    }

    @Test
    void aContinuedSectionRepeatsItsHeadingAndOverlapsBySentence() {
        String body = section("Cleaning", "Scrub the grill and wipe the counter before closing.", 60);

        List<String> chunks = DocumentChunker.chunk(body);

        assertTrue(chunks.size() >= 2);
        String second = chunks.get(1);
        assertTrue(second.startsWith("# Cleaning\n\n"), second);
        String lastSentenceOfFirst = chunks.getFirst().substring(chunks.getFirst().lastIndexOf("Scrub"));
        assertTrue(second.contains(lastSentenceOfFirst), "The second chunk starts with the end of the first");
    }

    @Test
    void sentencesAreNeverCutInHalf() {
        String paragraph = IntStream.rangeClosed(1, 120).mapToObj(i -> "Step " + i + " is to check the gas valve and the hose.")
                .collect(Collectors.joining(" "));

        List<String> chunks = DocumentChunker.chunk(paragraph);

        assertTrue(chunks.size() >= 2);
        chunks.forEach(chunk -> {
            assertTrue(chunk.startsWith("Step "), chunk.substring(0, 20));
            assertTrue(chunk.endsWith("hose."), chunk.substring(chunk.length() - 20));
        });
    }

    @Test
    void chineseTextIsCountedPerCharacterAndSplitAtSentenceEnds() {
        String paragraph = "每天早上九点前要订鸡肉，电话是九一二三四五六七。".repeat(40);

        List<String> chunks = DocumentChunker.chunk(paragraph);

        assertTrue(chunks.size() >= 2, "About 1,000 characters is more than one chunk");
        chunks.forEach(chunk -> {
            assertTrue(DocumentChunker.tokens(chunk) <= DocumentChunker.MAX_TOKENS + DocumentChunker.OVERLAP_TOKENS);
            assertTrue(chunk.endsWith("。"), chunk);
        });
    }

    @Test
    void aTableSplitsBetweenRows() {
        String table = IntStream.rangeClosed(1, 200).mapToObj(i -> "Item " + i + " | Supplier " + i + " | Order by 9pm")
                .collect(Collectors.joining("\n"));

        List<String> chunks = DocumentChunker.chunk(table);

        assertTrue(chunks.size() >= 2);
        chunks.forEach(chunk -> chunk.lines().filter(line -> !line.isBlank())
                .forEach(line -> assertTrue(line.matches("Item \\d+ \\| Supplier \\d+ \\| Order by 9pm"), line)));
    }

    @Test
    void aHeadingAloneIsNeverAChunk() {
        List<String> chunks = DocumentChunker.chunk(section("Rules", "Wash hands before serving.", 40) + "\n\n# Empty heading");

        assertFalse(chunks.contains("# Empty heading"));
    }

    @Test
    void anEnormousWordIsCut() {
        List<String> chunks = DocumentChunker.chunk("https://example.com/" + "a".repeat(5000));

        assertTrue(chunks.size() >= 3);
        chunks.forEach(chunk -> assertTrue(DocumentChunker.tokens(chunk) <= DocumentChunker.MAX_TOKENS));
    }

    private static String section(String heading, String sentence, int sentences) {
        return "# " + heading + "\n\n" + IntStream.range(0, sentences).mapToObj(i -> sentence).collect(Collectors.joining(" "));
    }
}
