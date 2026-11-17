package dev.nosurprise.common.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие, которое можно положить в outbox: у каждого есть eventId (ключ идемпотентности consumer-ов)
 * и время возникновения (INSTRUCTION.md §8).
 */
public interface OutboxMessage {

    UUID eventId();

    Instant occurredAt();
}
