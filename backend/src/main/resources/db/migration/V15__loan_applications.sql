-- V15: заявки на микрозайм, журнал событий, договоры и подписи (MICROLOANS.md §4.3).
CREATE TABLE loan_applications (
    id                             uuid PRIMARY KEY,
    user_id                        uuid          NOT NULL REFERENCES users (id),
    loan_type_id                   uuid          NOT NULL REFERENCES loan_types (id),
    amount                         numeric(12,2) NOT NULL CHECK (amount > 0),
    term_days                      integer       NOT NULL CHECK (term_days BETWEEN 7 AND 30),
    -- снимок условий на момент подачи (§3.6)
    daily_rate                     numeric(6,3)  NOT NULL,
    modifier                       numeric(12,2) NOT NULL,
    interest                       numeric(12,2) NOT NULL,
    total                          numeric(12,2) NOT NULL,
    -- снимок данных заявителя на момент подачи (§3.6)
    applicant_last_name            varchar(100)  NOT NULL,
    applicant_first_name           varchar(100)  NOT NULL,
    applicant_middle_name          varchar(100),
    applicant_phone                varchar(20)   NOT NULL,
    applicant_monthly_income       numeric(12,2) NOT NULL CHECK (applicant_monthly_income > 0),
    kyc_document_id                uuid          NOT NULL REFERENCES kyc_documents (id),
    status                         varchar(16)   NOT NULL CHECK (status IN
                                       ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'ACTIVE', 'EXPIRED', 'REPAID')),
    decided_by                     uuid,
    decided_at                     timestamptz,
    decision_comment               varchar(1000),
    offer_expires_at               timestamptz,
    closed_at                      timestamptz,
    credit_history_synced_event_id bigint,
    created_at                     timestamptz   NOT NULL DEFAULT now(),
    updated_at                     timestamptz   NOT NULL DEFAULT now(),
    version                        bigint        NOT NULL DEFAULT 0
);

-- Один активный микрозайм на пользователя гарантирует база, а не только код:
-- два параллельных «Подать» не создадут две заявки (§3.8, §6).
CREATE UNIQUE INDEX uq_loan_applications_one_active
    ON loan_applications (user_id) WHERE status IN ('SUBMITTED', 'APPROVED', 'ACTIVE');

CREATE INDEX idx_loan_applications_status_created ON loan_applications (status, created_at DESC);
CREATE INDEX idx_loan_applications_user_created ON loan_applications (user_id, created_at DESC);
CREATE INDEX idx_loan_applications_status_expires ON loan_applications (status, offer_expires_at);
CREATE INDEX idx_loan_applications_closed
    ON loan_applications (closed_at) WHERE status IN ('REJECTED', 'CANCELLED', 'EXPIRED');
CREATE INDEX idx_loan_applications_type ON loan_applications (loan_type_id);
CREATE INDEX idx_loan_applications_kyc ON loan_applications (kyc_document_id);

-- Журнал событий заявки: смены статусов и проколы. Из него собирается документ кредитной истории в Mongo (§3.14).
CREATE TABLE loan_events (
    id             bigserial PRIMARY KEY,
    application_id uuid        NOT NULL REFERENCES loan_applications (id),
    type           varchar(48) NOT NULL CHECK (type IN
                       ('STATUS_CHANGED', 'OFFER_NOT_SIGNED', 'SIGN_CODE_SENT', 'SIGN_CODE_FAILED',
                        'SIGN_CODE_BURNED', 'SIGN_BLOCKED_PHONE_NOT_VERIFIED')),
    from_status    varchar(16),
    to_status      varchar(16),
    actor_id       uuid,
    details        jsonb,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_loan_events_application ON loan_events (application_id, id);

CREATE TABLE loan_documents (
    id             uuid PRIMARY KEY,
    application_id uuid        NOT NULL REFERENCES loan_applications (id),
    type           varchar(16) NOT NULL CHECK (type IN ('CONTRACT')),
    gridfs_id      varchar(24) NOT NULL,
    sha256         varchar(64) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (application_id, type)
);

CREATE TABLE loan_signatures (
    id              uuid PRIMARY KEY,
    application_id  uuid         NOT NULL UNIQUE REFERENCES loan_applications (id),
    document_id     uuid         NOT NULL REFERENCES loan_documents (id),
    document_sha256 varchar(64)  NOT NULL,
    phone           varchar(20)  NOT NULL,
    ip              varchar(64),
    user_agent      varchar(512),
    signed_at       timestamptz  NOT NULL
);
