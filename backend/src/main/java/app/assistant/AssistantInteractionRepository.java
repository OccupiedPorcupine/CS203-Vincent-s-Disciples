package app.assistant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssistantInteractionRepository extends JpaRepository<AssistantInteraction, Long> {

    Optional<AssistantInteraction> findByIdAndOwnerId(Long id, Long ownerId);
}
