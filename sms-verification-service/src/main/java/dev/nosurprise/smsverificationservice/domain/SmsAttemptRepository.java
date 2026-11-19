package dev.nosurprise.smsverificationservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface SmsAttemptRepository extends JpaRepository<SmsAttempt, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM SmsAttempt a WHERE a.startedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}