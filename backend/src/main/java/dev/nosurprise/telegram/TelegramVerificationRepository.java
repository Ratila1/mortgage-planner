package dev.nosurprise.telegram;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface TelegramVerificationRepository extends JpaRepository<TelegramVerification, UUID> {

    /**
     * Atomically claims a pending verification. Concurrent callers serialize on the row:
     * the second update sees status COMPLETED and affects zero rows.
     */
    @Modifying
    @Query("""
            update TelegramVerification v
               set v.status = 'COMPLETED', v.completedAt = :completedAt
             where v.id = :id and v.status = 'PENDING'
            """)
    int claimCompletion(@Param("id") UUID id, @Param("completedAt") Instant completedAt);
}
