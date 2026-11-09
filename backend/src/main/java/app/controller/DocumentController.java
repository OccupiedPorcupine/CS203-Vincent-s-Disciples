package app.controller;

import app.document.DocumentRetriever;
import app.document.DocumentService;
import app.dto.RetrievedChunk;
import app.dto.DocumentRequest;
import app.dto.DocumentResponse;
import app.dto.DocumentSummaryResponse;
import app.user.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "Documents", description = "The owner's own rules and information, which the assistant answers from")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService service;
    private final DocumentRetriever retriever;

    public DocumentController(DocumentService service, DocumentRetriever retriever) {
        this.service = service;
        this.retriever = retriever;
    }

    @Operation(summary = "List documents", description = "Titles, source, index status, size and last edit, newest first. No text.")
    @GetMapping
    public List<DocumentSummaryResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.id());
    }

    @Operation(summary = "Search documents", description = "The parts of your documents most similar to q, best first, "
            + "with their scores: the search the assistant uses. all=true leaves out the similarity floor, for tuning it.")
    @GetMapping("/search")
    public List<RetrievedChunk> search(@AuthenticationPrincipal AuthenticatedUser user, @RequestParam("q") String query,
                                       @RequestParam(value = "k", defaultValue = "5") int k,
                                       @RequestParam(value = "all", defaultValue = "false") boolean all) {
        if (query.length() > 1000 || k < 1 || k > 20) {
            throw new IllegalArgumentException("q must be at most 1,000 characters and k from 1 to 20.");
        }
        return retriever.retrieve(user.id(), query, k, all ? -1 : retriever.minSimilarity()).stream()
                .map(hit -> new RetrievedChunk(hit.documentId(), hit.title(), hit.chunkId(), hit.score(), null, hit.content()))
                .toList();
    }

    @Operation(summary = "Get a document", description = "One document with its text.")
    @GetMapping("/{id}")
    public DocumentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.get(user.id(), id);
    }

    @Operation(summary = "Write a document", description = "Up to 100,000 characters of text, and 30 documents per stall.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DocumentResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestBody DocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.id(), request.title(), request.body()));
    }

    @Operation(summary = "Upload a document", description = "A .txt, .md, .pdf (with a text layer) or .docx file of up to 5 MB. "
            + "Only the text is kept. The title defaults to the file's name.")
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "title", required = false) String title) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(user.id(), file, title));
    }

    @Operation(summary = "Edit a document", description = "Replaces the title and text.")
    @PutMapping("/{id}")
    public DocumentResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                   @RequestBody DocumentRequest request) {
        return service.update(user.id(), id, request.title(), request.body());
    }

    @Operation(summary = "Index a document again", description = "For a document whose index status is FAILED: "
            + "embeds its text again so the assistant can search it.")
    @PostMapping("/{id}/index")
    public DocumentResponse reindex(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.reindex(user.id(), id);
    }

    @Operation(summary = "Delete a document")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        service.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
