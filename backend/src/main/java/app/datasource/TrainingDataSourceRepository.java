package app.datasource;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TrainingDataSourceRepository extends JpaRepository<TrainingDataSource, Long> {
    List<TrainingDataSource> findAllByOwnerIdOrderByUploadedAtDesc(Long ownerId);
    Optional<TrainingDataSource> findByOwnerIdAndSha256(Long ownerId, String sha256);
    Optional<TrainingDataSource> findByIdAndOwnerId(Long id, Long ownerId);
}
