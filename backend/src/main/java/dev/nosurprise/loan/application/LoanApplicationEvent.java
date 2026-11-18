package dev.nosurprise.loan.application;

import dev.nosurprise.common.outbox.OutboxMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * Бизнес-событие заявки для RabbitMQ: подана, одобрена, отклонена, подписана, истекла, погашена, договор готов.
 * Только идентификаторы и статус, без персональных данных (§4.5): consumer читает актуальное из Postgres.
 * status — статус заявки в момент события; для loan.contract.ready — {@link #CONTRACT_READY}.
 */
public record LoanApplicationEvent(UUID eventId, Instant occurredAt, UUID applicationId, UUID userId,
                                   String status) implements OutboxMessage {

    public static final String CONTRACT_READY = "CONTRACT_READY";
}
