package com.csd.salesforecast.repository;

import com.csd.salesforecast.domain.TrainingRunSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface TrainingRunSourceRepository extends JpaRepository<TrainingRunSourceEntity, UUID> {
    List<TrainingRunSourceEntity> findByTrainingRunId(UUID trainingRunId);
}
