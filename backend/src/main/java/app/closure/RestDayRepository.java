package app.closure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestDayRepository extends JpaRepository<RestDay, Long> {

    List<RestDay> findByOwnerId(Long ownerId);
}
