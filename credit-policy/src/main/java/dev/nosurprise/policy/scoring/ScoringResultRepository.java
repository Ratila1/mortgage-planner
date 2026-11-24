package dev.nosurprise.policy.scoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoringResultRepository extends JpaRepository<ScoringResult, UUID> {

    Optional<ScoringResult> findByRequestId(UUID requestId);

    Optional<ScoringResult> findFirstByApplicationIdOrderByCreatedAtDesc(UUID applicationId);

    List<ScoringResult> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}
