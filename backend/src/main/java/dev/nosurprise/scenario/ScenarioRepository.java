package dev.nosurprise.scenario;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {
    List<Scenario> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Scenario> findAllByIdInAndUserId(List<UUID> ids, UUID userId);

    java.util.Optional<Scenario> findByIdAndUserId(UUID id, UUID userId);

    List<Scenario> findByOfferId(UUID offerId);

    List<Scenario> findAllByOfferId(UUID offerId);
}