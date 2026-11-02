package app.closure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingHoursRepository extends JpaRepository<TradingHours, Long> {

    Optional<TradingHours> findByOwnerId(Long ownerId);
}
