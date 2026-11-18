-- V2: аудит запросов на 2FA/подпись операций.
CREATE TABLE verification_requests (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL,
    operation_type  varchar(64) NOT NULL,
    operation_ref   varchar(128) NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('PENDING','SENT','APPROVED','REJECTED','EXPIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    completed_at    timestamptz
);

CREATE INDEX idx_verification_requests_user ON verification_requests (user_id, created_at DESC);