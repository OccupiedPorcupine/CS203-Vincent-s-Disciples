package app.datasource;

import jakarta.persistence.*;

// The bytes of one uploaded file, kept apart from TrainingDataSource so listing uploads doesn't
// load them.
@Entity
@Table(name = "training_data_files")
public class TrainingDataFile {

    @Id
    private Long sourceId;

    @Column(nullable = false)
    private byte[] content;

    protected TrainingDataFile() {}

    public TrainingDataFile(Long sourceId, byte[] content) {
        this.sourceId = sourceId;
        this.content = content;
    }

    public byte[] getContent() { return content; }
}
