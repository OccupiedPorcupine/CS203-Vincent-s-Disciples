package app.actions;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DismissedActionRepository extends JpaRepository<DismissedAction, Long> {
    List<DismissedAction> findByOwnerId(Long ownerId);
    boolean existsByOwnerIdAndActionKey(Long ownerId, String actionKey);
}
