package dev.nosurprise.sms.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsAttemptRepository extends JpaRepository<SmsAttempt, Long> {
}
