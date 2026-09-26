package com.csd.salesforecast.repository;
import com.csd.salesforecast.domain.TrainingRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface TrainingRunRepository extends JpaRepository<TrainingRunEntity, UUID> {
    List<TrainingRunEntity> findTop20ByOrderByStartedAtDesc();
}
