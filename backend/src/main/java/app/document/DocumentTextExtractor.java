package app.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns an uploaded .txt, .md, .pdf or .docx file into plain text (issue #151). The type is checked
// by the file's content as well as its name, and parsing runs with a time limit and a page cap, so a
// hostile file can't hang a request. POI's zip-bomb limits stay at their defaults (on).
@Component
public class DocumentTextExtractor {

    static final int MAX_PDF_PAGES = 200;
    static final Duration TIME_LIMIT = Duration.ofSeconds(15);

    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)heading\\s*(\\d)|title");

    enum Kind { TEXT, PDF, DOCX }

    // Parsing runs here, so the request can stop waiting on a file that takes too long.
    private final ExecutorService parsers = Executors.newVirtualThreadPerTaskExecutor();

    public String extract(byte[] content, String fileName) {
        Kind kind = kindOf(content, fileName);
        Future<String> parsing = parsers.submit(() -> switch (kind) {
            case TEXT -> text(content);
            case PDF -> pdf(content);
            case DOCX -> docx(content);
        });
        try {
            return parsing.get(TIME_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            parsing.cancel(true);
            throw DocumentException.invalid("UNREADABLE_FILE", "This file took too long to read. Paste the text instead.");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof DocumentException documentException) {
                throw documentException;
            }
            throw unreadable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unreadable();
        }
    }

    // By the name first, then checked against the first bytes: a PDF starts with %PDF- (within its
    // first 1024 bytes), a .docx is a zip, and a text file is neither and has no zero bytes.
    static Kind kindOf(byte[] content, String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean pdf = startsWithPdf(content);
        boolean zip = startsWith(content, new byte[] {'P', 'K', 3, 4});
        boolean oldWord = startsWith(content, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});

        if (name.endsWith(".pdf")) {
            if (!pdf) throw notWhatItSays("PDF");
            return Kind.PDF;
        }
        if (name.endsWith(".docx")) {
            if (!zip) throw notWhatItSays("Word (.docx)");
            return Kind.DOCX;
        }
        if (name.endsWith(".doc")) {
            throw DocumentException.invalid("UNSUPPORTED_FILE", "Older .doc files can't be read. Save it as .docx in Word, then upload that.");
        }
        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")) {
            if (pdf || zip || oldWord || (hasZeroByte(content) && !isUtf16(content))) throw notWhatItSays("text");
            return Kind.TEXT;
        }
        throw DocumentException.invalid("UNSUPPORTED_FILE", "Upload a .txt, .md, .pdf or .docx file.");
    }

    private static String text(byte[] content) {
        if (isUtf16(content)) {
            return new String(content, StandardCharsets.UTF_16);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException notUtf8) {
            // Older Windows editors save in this encoding.
            return new String(content, Charset.forName("windows-1252"));
        }
    }

    private static String pdf(byte[] content) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(content)) {
            if (pdf.getNumberOfPages() > MAX_PDF_PAGES) {
                throw DocumentException.invalid("FILE_TOO_LARGE",
                        "This PDF has more than " + MAX_PDF_PAGES + " pages. Upload the part Chicky needs to know.");
            }
            String text = new PDFTextStripper().getText(pdf);
            if (text.strip().isEmpty()) {
                throw DocumentException.invalid("NO_TEXT_IN_PDF", "This PDF has no text. Paste the text instead.");
            }
            return text;
        } catch (InvalidPasswordException exception) {
            throw DocumentException.invalid("UNREADABLE_FILE",
                    "This PDF is protected by a password. Remove the password or paste the text instead.");
        }
    }

    // Headings become "## Heading" lines and list items "- item", so chunking can split on them
    // (issue #152). Table rows become "cell | cell" lines.
    private static String docx(byte[] content) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> blocks = new ArrayList<>();
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText().strip();
                    if (text.isEmpty()) continue;
                    blocks.add(headingPrefix(paragraph.getStyle()) + (paragraph.getNumID() != null ? "- " : "") + text);
                } else if (element instanceof XWPFTable table) {
                    List<String> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) cells.add(cell.getText().strip().replace('\n', ' '));
                        rows.add(String.join(" | ", cells));
                    }
                    blocks.add(String.join("\n", rows));
                }
            }
            return String.join("\n\n", blocks);
        }
    }

    private static String headingPrefix(String style) {
        if (style == null) return "";
        Matcher heading = HEADING_STYLE.matcher(style.strip());
        if (!heading.matches()) return "";
        int level = heading.group(1) == null ? 1 : Math.min(Integer.parseInt(heading.group(1)), 6);
        return "#".repeat(level) + " ";
    }

    private static boolean startsWithPdf(byte[] content) {
        int limit = Math.min(content.length, 1024);
        String start = new String(content, 0, limit, StandardCharsets.ISO_8859_1);
        return start.contains("%PDF-");
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean isUtf16(byte[] content) {
        return startsWith(content, new byte[] {(byte) 0xFF, (byte) 0xFE}) || startsWith(content, new byte[] {(byte) 0xFE, (byte) 0xFF});
    }

    private static boolean hasZeroByte(byte[] content) {
        for (byte b : content) {
            if (b == 0) return true;
        }
        return false;
    }

    private static DocumentException notWhatItSays(String kind) {
        return DocumentException.invalid("UNSUPPORTED_FILE", "This file isn't a " + kind + " file inside. Check it opens, then try again.");
    }

    private static DocumentException unreadable() {
        return DocumentException.invalid("UNREADABLE_FILE", "This file couldn't be read. Check it opens, or paste the text instead.");
    }
}
