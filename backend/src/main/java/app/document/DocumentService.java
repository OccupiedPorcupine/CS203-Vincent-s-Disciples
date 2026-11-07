package app.document;

import app.document.StallDocument.Source;
import app.dto.DocumentResponse;
import app.dto.DocumentSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.NumberFormat;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// The owner's documents for the assistant (issue #151): write, upload, edit and delete. Every
// lookup is by owner, so another account's document is "not found". Documents are reference
// material for the assistant only; they never change forecasts, prep lists or prices.
// A document is saved first, then indexed for search (DocumentIndexer, #152) outside the
// transaction, so a slow or failed embedding call never loses the owner's text.
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    static final int MAX_DOCUMENTS = 30;
    static final int MAX_CHARACTERS = 100_000;
    static final int MAX_TITLE = 120;
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final StallDocumentRepository documents;
    private final DocumentTextExtractor extractor;
    private final DocumentIndexer indexer;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public DocumentService(StallDocumentRepository documents, DocumentTextExtractor extractor, DocumentIndexer indexer,
                           TransactionTemplate transactions, Clock clock) {
        this.documents = documents;
        this.extractor = extractor;
        this.indexer = indexer;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<DocumentSummaryResponse> list(Long ownerId) {
        return documents.summaries(ownerId).stream().map(DocumentSummaryResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(Long ownerId, Long id) {
        return DocumentResponse.of(find(ownerId, id));
    }

    public DocumentResponse create(Long ownerId, String title, String body) {
        return indexed(ownerId, save(ownerId, title, body, Source.TYPED, null));
    }

    // The original file isn't kept: only its text, which the owner can then edit.
    public DocumentResponse upload(Long ownerId, MultipartFile file, String title) {
        if (file == null || file.isEmpty()) {
            throw DocumentException.invalid("NO_FILE", "Choose a file to upload.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new DocumentException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Files can be up to 5 MB. This one is bigger.");
        }
        checkRoom(ownerId);
        String fileName = safeFileName(file.getOriginalFilename());
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw DocumentException.invalid("UNREADABLE_FILE", "The file couldn't be read. Please try again.");
        }
        String text = extractor.extract(content, fileName);
        String chosenTitle = title == null || title.isBlank() ? titleFromFileName(fileName) : title;
        Long id = save(ownerId, chosenTitle, text, Source.UPLOAD, fileName);
        log.info("Document #{} for user {} from {} ({} bytes): {} characters", id, ownerId, fileName, content.length, text.length());
        return indexed(ownerId, id);
    }

    public DocumentResponse update(Long ownerId, Long id, String title, String body) {
        String cleanTitle = cleanTitle(title);
        String cleanBody = cleanBody(body);
        boolean changed = Boolean.TRUE.equals(transactions.execute(transaction -> {
            StallDocument document = find(ownerId, id);
            document.edit(cleanTitle, cleanBody, clock.instant());
            return document.getIndexStatus() == StallDocument.IndexStatus.PENDING;
        }));
        return changed ? indexed(ownerId, id) : get(ownerId, id);
    }

    // "Try again" on a document that couldn't be indexed.
    public DocumentResponse reindex(Long ownerId, Long id) {
        get(ownerId, id);
        return indexed(ownerId, id);
    }

    @Transactional
    public void delete(Long ownerId, Long id) {
        documents.delete(find(ownerId, id));
        log.info("Document #{} for user {} deleted", id, ownerId);
    }

    // Saves a new document (in its own transaction) and returns its ID.
    private Long save(Long ownerId, String title, String body, Source source, String fileName) {
        String cleanTitle = cleanTitle(title);
        String cleanBody = cleanBody(body);
        return transactions.execute(transaction -> {
            checkRoom(ownerId);
            return documents.save(new StallDocument(ownerId, cleanTitle, cleanBody, source, fileName, clock.instant())).getId();
        });
    }

    private DocumentResponse indexed(Long ownerId, Long id) {
        indexer.index(id);
        return get(ownerId, id);
    }

    private void checkRoom(Long ownerId) {
        if (documents.countByOwnerId(ownerId) >= MAX_DOCUMENTS) {
            throw new DocumentException(HttpStatus.CONFLICT, "TOO_MANY_DOCUMENTS",
                    "You have " + MAX_DOCUMENTS + " documents, the most a stall can have. Delete one or add to an existing one.");
        }
    }

    private StallDocument find(Long ownerId, Long id) {
        return documents.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new DocumentException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "Document not found."));
    }

    private static String cleanTitle(String title) {
        String cleaned = title == null ? "" : CONTROL.matcher(title).replaceAll(" ").strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            throw DocumentException.invalid("TITLE_REQUIRED", "Give the document a title.");
        }
        if (cleaned.length() > MAX_TITLE) {
            throw DocumentException.invalid("TITLE_TOO_LONG", "Keep the title to " + MAX_TITLE + " characters.");
        }
        return cleaned;
    }

    // Plain text with \n line ends: no byte order mark or control characters, no trailing spaces,
    // and at most one blank line in a row. PDF page breaks become blank lines.
    static String cleanBody(String body) {
        String text = body == null ? "" : body.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').replace("\f", "\n\n");
        text = CONTROL.matcher(text).replaceAll("");
        text = TRAILING_SPACE.matcher(text).replaceAll("\n");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
        if (text.isEmpty()) {
            throw DocumentException.invalid("BODY_REQUIRED", "Write something in the document.");
        }
        if (text.length() > MAX_CHARACTERS) {
            NumberFormat number = NumberFormat.getIntegerInstance(Locale.ENGLISH);
            throw DocumentException.invalid("DOCUMENT_TOO_LONG", "Documents can be up to " + number.format(MAX_CHARACTERS)
                    + " characters. This one has " + number.format(text.length()) + ". Split it into smaller documents.");
        }
        return text;
    }

    // "Ah_Huat-suppliers.docx" -> "Ah Huat suppliers".
    static String titleFromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String title = (dot > 0 ? fileName.substring(0, dot) : fileName).replaceAll("[_-]+", " ").strip();
        if (title.isEmpty()) title = "Uploaded document";
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE).strip() : title;
    }

    private static String safeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        name = CONTROL.matcher(name).replaceAll("");
        if (name.isEmpty()) {
            throw DocumentException.invalid("UNSUPPORTED_FILE", "Upload a .txt, .md, .pdf or .docx file.");
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
