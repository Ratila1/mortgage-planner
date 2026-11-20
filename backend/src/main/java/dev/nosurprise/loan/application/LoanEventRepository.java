package dev.nosurprise.loan.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LoanEventRepository extends JpaRepository<LoanEvent, Long> {

    List<LoanEvent> findByApplicationIdOrderByIdAsc(UUID applicationId);

    List<LoanEvent> findByOccurredAtBetween(java.time.Instant from, java.time.Instant to);

    List<LoanEvent> findByTypeAndOccurredAtBetween(LoanEventType type, java.time.Instant from, java.time.Instant to);
}
