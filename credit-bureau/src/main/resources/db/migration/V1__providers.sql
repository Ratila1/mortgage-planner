-- V1: провайдеры (внешние банки), их API-ключи и журнал запросов (CREDIT_BUREAU.md §6.1).

CREATE TABLE providers (
    id                  uuid PRIMARY KEY,
    name                varchar(200) NOT NULL UNIQUE,
    status              varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    -- песочница видит только фиктивных заёмщиков (§7.3)
    sandbox             boolean      NOT NULL DEFAULT false,
    requests_per_minute integer      NOT NULL CHECK (requests_per_minute > 0),
    requests_per_day    integer      NOT NULL CHECK (requests_per_day > 0),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0
);

-- Несколько ключей на провайдера: ротация без простоя (выдали новый, банк переключился, старый отозвали).
-- Хранится только sha256 секретной части; по префиксу ищется строка.
CREATE TABLE provider_keys (
    id           uuid PRIMARY KEY,
    provider_id  uuid        NOT NULL REFERENCES providers (id),
    key_prefix   varchar(16) NOT NULL UNIQUE,
    key_hash     varchar(64) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);

CREATE INDEX idx_provider_keys_provider ON provider_keys (provider_id);

-- Журнал запросов: кто, по каким параметрам, что ответили. По нему считаются деньги.
-- ФИО и телефон не хранятся — только sha256 нормализованных параметров (§6.1, §11): для разбора споров
-- банк присылает те же параметры, мы сравниваем хеш.
CREATE TABLE report_requests (
    id              uuid PRIMARY KEY,
    provider_id     uuid        NOT NULL REFERENCES providers (id),
    key_id          uuid        NOT NULL REFERENCES provider_keys (id),
    search_by       varchar(16) NOT NULL CHECK (search_by IN ('PHONE', 'NAME', 'PHONE_AND_NAME')),
    query_hash      varchar(64) NOT NULL,
    result          varchar(16) NOT NULL CHECK (result IN ('FOUND', 'NOT_FOUND', 'AMBIGUOUS')),
    billable        boolean     NOT NULL,
    idempotency_key varchar(100),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_report_requests_idempotency ON report_requests (provider_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_report_requests_provider_created ON report_requests (provider_id, created_at);
