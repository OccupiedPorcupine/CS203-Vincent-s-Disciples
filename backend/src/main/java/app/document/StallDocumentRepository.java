package app.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StallDocumentRepository extends JpaRepository<StallDocument, Long> {

    Optional<StallDocument> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    @Query("select d.id from StallDocument d where d.indexStatus = :status")
    List<Long> findIdsByIndexStatus(StallDocument.IndexStatus status);

    // The list on the Documents page, without loading every document's text.
    @Query("""
            select d.id as id, d.title as title, d.source as source, d.fileName as fileName,
                   d.indexStatus as indexStatus, length(d.body) as characters, d.updatedAt as updatedAt
            from StallDocument d where d.ownerId = :ownerId order by d.updatedAt desc, d.id desc
            """)
    List<Summary> summaries(Long ownerId);

    interface Summary {
        Long getId();

        String getTitle();

        StallDocument.Source getSource();

        String getFileName();

        StallDocument.IndexStatus getIndexStatus();

        Integer getCharacters();

        Instant getUpdatedAt();
    }
}
