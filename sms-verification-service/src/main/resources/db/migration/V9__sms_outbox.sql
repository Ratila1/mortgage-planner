-- V9: transactional outbox for Telegram delivery after SMS is delivered.
-- The row is written in the same DB transaction as the SMS status transition, so delivery intent
-- is not lost if the process crashes before calling Telegram.

CREATE TABLE sms.outbox_event (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type     VARCHAR(64) NOT NULL,
    sms_id         UUID        NOT NULL,
    status         VARCHAR(32) NOT NULL,
    attempts       INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error     TEXT,
    locked_until   TIMESTAMPTZ,
    locked_by      VARCHAR(128),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ,
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('NEW', 'RETRY', 'PROCESSED', 'DEAD'))
);

CREATE UNIQUE INDEX uq_outbox_event_type_sms_id ON sms.outbox_event (event_type, sms_id);
CREATE INDEX idx_outbox_event_poll ON sms.outbox_event (status, next_attempt_at, created_at)
    WHERE status IN ('NEW', 'RETRY');

COMMENT ON TABLE sms.outbox_event IS
    'Transactional outbox for SMS side effects; SMS_DELIVERED drives idempotent Telegram delivery';
