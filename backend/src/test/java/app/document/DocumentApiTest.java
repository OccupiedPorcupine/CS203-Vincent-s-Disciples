package app.document;

import app.service.PublicHolidaySyncService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The Documents API (issue #151) over real HTTP and a real (H2) database: typed and uploaded
// documents, the limits, and that one owner can't see or change another's documents.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @MockitoBean
    PublicHolidaySyncService publicHolidaySyncService;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void deleteDocuments() {
        jdbc.update("DELETE FROM stall_documents");
    }

    @Test
    void aTypedDocumentCanBeWrittenReadEditedAndDeleted() throws Exception {
        Session owner = signUp();

        HttpResponse<String> created = owner.json("POST", "/api/documents",
                "{\"title\":\"  Ah Huat Poultry \",\"body\":\"Order by 9pm.\\r\\n\\r\\n\\r\\n\\r\\nCall 9123 4567.  \"}");
        assertEquals(201, created.statusCode(), created.body());
        JsonNode document = JSON.readTree(created.body());
        long id = document.path("id").asLong();
        assertEquals("Ah Huat Poultry", document.path("title").asString());
        assertEquals("Order by 9pm.\n\nCall 9123 4567.", document.path("body").asString());
        assertEquals("TYPED", document.path("source").asString());
        assertEquals("PENDING", document.path("indexStatus").asString());

        JsonNode list = JSON.readTree(owner.get("/api/documents").body());
        assertEquals(1, list.size());
        assertEquals("Ah Huat Poultry", list.get(0).path("title").asString());
        assertEquals(30, list.get(0).path("characters").asInt());
        assertFalse(list.get(0).has("body"), "The list doesn't send the text");

        HttpResponse<String> edited = owner.json("PUT", "/api/documents/" + id, "{\"title\":\"Ah Huat Poultry\",\"body\":\"Order by 8pm now.\"}");
        assertEquals(200, edited.statusCode(), edited.body());
        assertEquals("Order by 8pm now.", JSON.readTree(owner.get("/api/documents/" + id).body()).path("body").asString());

        assertEquals(204, owner.send("DELETE", "/api/documents/" + id, null, null).statusCode());
        assertEquals(404, owner.get("/api/documents/" + id).statusCode());
    }

    @Test
    void missingTitleOrTextIsRefusedWithAReason() throws Exception {
        Session owner = signUp();

        HttpResponse<String> noTitle = owner.json("POST", "/api/documents", "{\"title\":\" \",\"body\":\"Something\"}");
        assertEquals(400, noTitle.statusCode());
        assertTrue(noTitle.body().contains("\"code\":\"TITLE_REQUIRED\""), noTitle.body());

        HttpResponse<String> noBody = owner.json("POST", "/api/documents", "{\"title\":\"Rules\",\"body\":\"\\n\\n\"}");
        assertEquals(400, noBody.statusCode());
        assertTrue(noBody.body().contains("\"code\":\"BODY_REQUIRED\""), noBody.body());

        HttpResponse<String> tooLong = owner.json("POST", "/api/documents",
                "{\"title\":\"Rules\",\"body\":\"" + "a".repeat(DocumentService.MAX_CHARACTERS + 1) + "\"}");
        assertEquals(400, tooLong.statusCode());
        assertTrue(tooLong.body().contains("\"code\":\"DOCUMENT_TOO_LONG\""), tooLong.body());
    }

    @Test
    void textMarkdownPdfAndWordFilesBecomeDocuments() throws Exception {
        Session owner = signUp();

        JsonNode text = JSON.readTree(assertCreated(owner.upload("Helper_rules.txt", "Wash hands before serving.".getBytes(StandardCharsets.UTF_8), null)));
        assertEquals("Helper rules", text.path("title").asString());
        assertEquals("Wash hands before serving.", text.path("body").asString());
        assertEquals("UPLOAD", text.path("source").asString());
        assertEquals("Helper_rules.txt", text.path("fileName").asString());

        JsonNode markdown = JSON.readTree(assertCreated(owner.upload("gas.md", "# Gas runs out\n\nCall 6123 4567.".getBytes(StandardCharsets.UTF_8), "When the gas runs out")));
        assertEquals("When the gas runs out", markdown.path("title").asString());
        assertTrue(markdown.path("body").asString().startsWith("# Gas runs out"));

        JsonNode pdf = JSON.readTree(assertCreated(owner.upload("cleaning.pdf", pdf("Scrub the grill every Sunday."), null)));
        assertEquals("Scrub the grill every Sunday.", pdf.path("body").asString());

        JsonNode word = JSON.readTree(assertCreated(owner.upload("suppliers.docx", docx(), null)));
        assertEquals("# Suppliers\n\nAh Huat Poultry: order by 9pm.\n\nItem | Supplier\nEggs | Kim Lee", word.path("body").asString());
    }

    @Test
    void badFilesAreRefusedWithClearMessages() throws Exception {
        Session owner = signUp();

        HttpResponse<String> scanned = owner.upload("scan.pdf", pdf(null), null);
        assertEquals(400, scanned.statusCode());
        assertTrue(scanned.body().contains("This PDF has no text. Paste the text instead."), scanned.body());

        HttpResponse<String> big = owner.upload("big.txt", new byte[(int) DocumentService.MAX_FILE_BYTES + 1], null);
        assertEquals(413, big.statusCode());
        assertTrue(big.body().contains("\"code\":\"FILE_TOO_LARGE\""), big.body());

        HttpResponse<String> fakePdf = owner.upload("notes.pdf", "just text".getBytes(StandardCharsets.UTF_8), null);
        assertEquals(400, fakePdf.statusCode());
        assertTrue(fakePdf.body().contains("\"code\":\"UNSUPPORTED_FILE\""), fakePdf.body());

        HttpResponse<String> pdfAsText = owner.upload("notes.txt", pdf("Hidden PDF"), null);
        assertEquals(400, pdfAsText.statusCode());

        HttpResponse<String> brokenWord = owner.upload("broken.docx", new byte[] {'P', 'K', 3, 4, 1, 2, 3}, null);
        assertEquals(400, brokenWord.statusCode());
        assertTrue(brokenWord.body().contains("\"code\":\"UNREADABLE_FILE\""), brokenWord.body());

        HttpResponse<String> image = owner.upload("menu.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}, null);
        assertEquals(400, image.statusCode());
        assertTrue(image.body().contains("Upload a .txt, .md, .pdf or .docx file."), image.body());

        assertEquals(0, JSON.readTree(owner.get("/api/documents").body()).size());
    }

    @Test
    void theThirtyFirstDocumentIsRefused() throws Exception {
        Session owner = signUp();
        for (int i = 1; i <= DocumentService.MAX_DOCUMENTS; i++) {
            assertEquals(201, owner.json("POST", "/api/documents", "{\"title\":\"Note " + i + "\",\"body\":\"Text\"}").statusCode());
        }

        HttpResponse<String> typed = owner.json("POST", "/api/documents", "{\"title\":\"One more\",\"body\":\"Text\"}");
        assertEquals(409, typed.statusCode());
        assertTrue(typed.body().contains("\"code\":\"TOO_MANY_DOCUMENTS\""), typed.body());
        assertEquals(409, owner.upload("more.txt", "Text".getBytes(StandardCharsets.UTF_8), null).statusCode());
    }

    @Test
    void anotherOwnerGetsNotFoundOnEveryEndpoint() throws Exception {
        Session ownerA = signUp();
        Session ownerB = signUp();
        long id = JSON.readTree(ownerA.json("POST", "/api/documents", "{\"title\":\"Secret recipe\",\"body\":\"Two spoons of sugar.\"}").body())
                .path("id").asLong();

        assertEquals(404, ownerB.get("/api/documents/" + id).statusCode());
        assertEquals(404, ownerB.json("PUT", "/api/documents/" + id, "{\"title\":\"Mine now\",\"body\":\"Gone\"}").statusCode());
        assertEquals(404, ownerB.send("DELETE", "/api/documents/" + id, null, null).statusCode());
        assertEquals(0, JSON.readTree(ownerB.get("/api/documents").body()).size());

        assertEquals("Two spoons of sugar.", JSON.readTree(ownerA.get("/api/documents/" + id).body()).path("body").asString());
    }

    @Test
    void documentsNeedASignedInUser() throws Exception {
        assertEquals(401, new Session().get("/api/documents").statusCode());
    }

    private static String assertCreated(HttpResponse<String> response) {
        assertEquals(201, response.statusCode(), response.body());
        return response.body();
    }

    // A one-page PDF with the text, or a blank page (like a scan with no text layer) when text is null.
    private static byte[] pdf(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx() throws IOException {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Suppliers");
            document.createParagraph().createRun().setText("Ah Huat Poultry: order by 9pm.");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Item");
            table.getRow(0).getCell(1).setText("Supplier");
            table.getRow(1).getCell(0).setText("Eggs");
            table.getRow(1).getCell(1).setText("Kim Lee");
            document.write(out);
            return out.toByteArray();
        }
    }

    private Session signUp() throws Exception {
        Session session = new Session();
        String email = "docs-" + UUID.randomUUID() + "@documents.test";
        HttpResponse<String> signup = session.json("POST", "/api/auth/signup",
                "{\"email\":\"" + email + "\",\"password\":\"a-long-unique-password\",\"name\":\"Stall owner\"}");
        assertEquals(201, signup.statusCode(), signup.body());
        return session;
    }

    private final class Session {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> json(String method, String path, String body) throws Exception {
            return send(method, path, "application/json", HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> upload(String fileName, byte[] content, String title) throws Exception {
            String boundary = "----chicky" + UUID.randomUUID();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (title != null) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n" + title + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
                    + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(content);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return send("POST", "/api/documents/upload", "multipart/form-data; boundary=" + boundary,
                    HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        }

        HttpResponse<String> send(String method, String path, String contentType, HttpRequest.BodyPublisher body) throws Exception {
            String csrf = get("/api/csrf").body();
            Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(csrf);
            assertTrue(matcher.find(), csrf);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                    .header("X-XSRF-TOKEN", matcher.group(1))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : body);
            if (contentType != null) request.header("Content-Type", contentType);
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        URI uri(String path) {
            return URI.create("http://localhost:" + port + path);
        }
    }
}
