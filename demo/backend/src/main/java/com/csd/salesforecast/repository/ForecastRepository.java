package com.csd.salesforecast.repository;
import com.csd.salesforecast.domain.ForecastEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface ForecastRepository extends JpaRepository<ForecastEntity, UUID> {}
