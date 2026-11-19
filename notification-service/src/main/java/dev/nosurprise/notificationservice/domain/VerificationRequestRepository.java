package dev.nosurprise.notificationservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, UUID> {
    Optional<VerificationRequest> findByIdAndUserId(UUID id, UUID userId);
}