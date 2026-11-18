package dev.nosurprise.loan.application;

import dev.nosurprise.common.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/** В журнал заявки добавилась строка: копию в Mongo надо пересобрать (§4.9). */
public record LoanHistoryChanged(UUID eventId, Instant occurredAt, UUID applicationId) implements OutboxMessage {
}
