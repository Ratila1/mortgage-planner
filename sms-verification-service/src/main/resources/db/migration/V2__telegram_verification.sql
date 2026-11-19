-- Telegram binding and 2FA request audit tables.
-- Ported from notification-service; stored in the existing sms schema.

CREATE TABLE sms.telegram_bindings (
    user_id     uuid PRIMARY KEY,
    phone       varchar(20)  NOT NULL,
    telegram_id bigint       NOT NULL UNIQUE,
    username    varchar(64),
    linked_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);

CREATE INDEX idx_telegram_bindings_phone ON sms.telegram_bindings (phone);

CREATE TABLE sms.verification_requests (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL,
    operation_type  varchar(64) NOT NULL,
    operation_ref   varchar(128) NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('PENDING','SENT','APPROVED','REJECTED','EXPIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    completed_at    timestamptz
);

CREATE INDEX idx_verification_requests_user ON sms.verification_requests (user_id, created_at DESC);

COMMENT ON TABLE sms.telegram_bindings IS 'Telegram account bindings for 2FA';
COMMENT ON TABLE sms.verification_requests IS 'Audit log of Telegram 2FA requests';
