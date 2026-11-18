-- V9: transactional outbox (MICROLOANS.md §4.8).
-- Событие пишется в outbox_events в той же транзакции, что и бизнес-данные; relay отправляет его в RabbitMQ.
CREATE TABLE outbox_events (
    id             bigserial PRIMARY KEY,
    event_id       uuid        NOT NULL UNIQUE,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id   varchar(64) NOT NULL,
    event_type     varchar(128) NOT NULL,          -- routing key в domain.events
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz,
    attempts       integer     NOT NULL DEFAULT 0,
    last_error     text
);

-- Relay выбирает только неотправленные строки: частичный индекс остаётся маленьким.
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- Идемпотентность consumer-ов: (consumer, event_id) вставляется в транзакции обработки.
CREATE TABLE processed_events (
    consumer     varchar(64) NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
