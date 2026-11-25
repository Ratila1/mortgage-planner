package dev.nosurprise.bureau.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, UUID> {

    Page<ReportRequest> findByProviderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            UUID providerId, Instant from, Instant to, Pageable pageable);

    long countByProviderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(UUID providerId, Instant from, Instant to);

    long countByProviderIdAndBillableTrueAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(UUID providerId,
                                                                                          Instant from, Instant to);
}
