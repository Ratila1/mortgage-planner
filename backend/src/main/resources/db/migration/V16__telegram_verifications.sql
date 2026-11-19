-- Telegram 2FA requests created by the main backend.
CREATE TABLE telegram_verifications (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL,
    operation_type  varchar(64) NOT NULL,
    operation_ref   varchar(128) NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','COMPLETED','EXPIRED','CANCELLED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    completed_at    timestamptz
);

CREATE INDEX idx_telegram_verifications_user ON telegram_verifications (user_id, created_at DESC);
