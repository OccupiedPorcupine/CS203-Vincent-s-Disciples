package app.datasource;

import app.user.AppUser;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
        name = "training_data_sources",
        uniqueConstraints = @UniqueConstraint(
                name = "training_source_owner_hash_unique",
                columnNames = {"owner_id", "sha256"}
        )
)
public class TrainingDataSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 1024)
    private String storedPath;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private boolean usedByModel;

    @Column(nullable = false)
    private Instant uploadedAt;

    // The latest import into daily sales; null until imported.
    private Instant importedAt;
    private Integer importRowsAdded;
    private Integer importRowsOverwritten;
    private Integer importRowsSkipped;

    protected TrainingDataSource() {}

    public TrainingDataSource(AppUser owner, String originalFilename, String contentType,
                              long sizeBytes, String sha256, String storedPath) {
        this.owner = owner;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storedPath = storedPath;
        this.status = "AVAILABLE_FOR_TRAINING";
        this.usedByModel = false;
        this.uploadedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStatus() { return status; }
    public boolean isUsedByModel() { return usedByModel; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getStoredPath() { return storedPath; }
    public Instant getImportedAt() { return importedAt; }
    public Integer getImportRowsAdded() { return importRowsAdded; }
    public Integer getImportRowsOverwritten() { return importRowsOverwritten; }
    public Integer getImportRowsSkipped() { return importRowsSkipped; }

    public void recordImport(Instant at, int added, int overwritten, int skipped) {
        this.importedAt = at;
        this.importRowsAdded = added;
        this.importRowsOverwritten = overwritten;
        this.importRowsSkipped = skipped;
    }
}
