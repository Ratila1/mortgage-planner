package dev.nosurprise.loan.contract;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanDocumentRepository extends JpaRepository<LoanDocument, UUID> {

    Optional<LoanDocument> findByApplicationIdAndType(UUID applicationId, String type);

    List<LoanDocument> findByApplicationIdOrderByCreatedAt(UUID applicationId);

    Optional<LoanDocument> findByIdAndApplicationId(UUID id, UUID applicationId);
}
