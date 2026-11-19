package dev.nosurprise.telegram;

import dev.nosurprise.common.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/** Payload of the verification.requested event. */
public record VerificationRequested(
        UUID eventId,
        Instant occurredAt,
        UUID verificationId,
        UUID userId,
        String operationType,
        String operationRef,
        int ttlMinutes
) implements OutboxMessage {}
