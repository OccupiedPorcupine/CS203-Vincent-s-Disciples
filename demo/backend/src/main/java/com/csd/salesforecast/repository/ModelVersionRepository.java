package com.csd.salesforecast.repository;
import com.csd.salesforecast.domain.ModelVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ModelVersionRepository extends JpaRepository<ModelVersionEntity, UUID> {
    Optional<ModelVersionEntity> findFirstByActiveTrueOrderByCreatedAtDesc();
    List<ModelVersionEntity> findAllByActiveTrue();
    List<ModelVersionEntity> findAllByOrderByCreatedAtDesc();
}
