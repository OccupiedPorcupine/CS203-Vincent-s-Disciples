package com.csd.salesforecast.repository;
import com.csd.salesforecast.domain.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface DataSourceRepository extends JpaRepository<DataSourceEntity, UUID> {
    Optional<DataSourceEntity> findBySha256(String sha256);
    List<DataSourceEntity> findByIncludedTrueAndStatusOrderByCreatedAtDesc(String status);
    List<DataSourceEntity> findAllByOrderByCreatedAtDesc();
}
