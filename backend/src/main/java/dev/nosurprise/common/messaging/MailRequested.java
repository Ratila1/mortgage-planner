package dev.nosurprise.common.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие mail.requested (§8): JSON-record с eventId (UUID) и occurredAt (Instant),
 * плюс данные письма. Публикуется в topic-exchange domain.events, consuming — mail.send.q.
 */
public record MailRequested(UUID eventId, Instant occurredAt, String to, String subject, String body) {
}
