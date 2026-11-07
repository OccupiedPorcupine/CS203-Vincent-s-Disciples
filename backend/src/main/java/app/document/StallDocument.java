package app.document;

import jakarta.persistence.*;

import java.time.Instant;

// One of the owner's documents for the assistant: its title and plain text. See V36__stall_documents.sql.
@Entity
@Table(name = "stall_documents")
public class StallDocument {

    public enum Source { TYPED, UPLOAD, ASSISTANT }

    // Whether the text is searchable by the assistant yet (issue #152).
    public enum IndexStatus { PENDING, INDEXED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 100000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    // The uploaded file's name; null unless the document came from a file.
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IndexStatus indexStatus;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected StallDocument() {
    }

    public StallDocument(Long ownerId, String title, String body, Source source, String fileName, Instant createdAt) {
        this.ownerId = ownerId;
        this.title = title;
        this.body = body;
        this.source = source;
        this.fileName = fileName;
        this.indexStatus = IndexStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // New text needs indexing again; a new title alone does too, since every chunk starts with it.
    public void edit(String title, String body, Instant at) {
        if (!title.equals(this.title) || !body.equals(this.body)) {
            indexStatus = IndexStatus.PENDING;
        }
        this.title = title;
        this.body = body;
        this.updatedAt = at;
    }

    public void markIndexStatus(IndexStatus indexStatus) {
        this.indexStatus = indexStatus;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Source getSource() {
        return source;
    }

    public String getFileName() {
        return fileName;
    }

    public IndexStatus getIndexStatus() {
        return indexStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
