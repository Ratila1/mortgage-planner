-- SMS Verification Service Schema
-- Schema: sms (configured via spring.datasource.hikari.schema)

CREATE SCHEMA IF NOT EXISTS sms;

-- Sequence for sms_attempt.id
CREATE SEQUENCE IF NOT EXISTS sms.sms_attempt_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- ============================================================
-- sms_request: Основная таблица запросов на отправку SMS
-- ============================================================
CREATE TABLE sms.sms_request (
  id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  idempotency_key         text NOT NULL UNIQUE,
  phone                   varchar(16) NOT NULL,
  language                varchar(2)  NOT NULL CHECK (language IN ('RU','EN')),
  message                 text        NOT NULL,
  status                  varchar(16) NOT NULL CHECK (status IN ('IN_PROGRESS','SUCCESS','NOT_SEND')) DEFAULT 'IN_PROGRESS',
  attempts                int NOT NULL DEFAULT 0 CHECK (attempts <= max_attempts),
  max_attempts            int NOT NULL DEFAULT 3,
  next_attempt_at         timestamptz,
  provider_message_id     text,
  last_error              text,
  locked_until            timestamptz,
  locked_by               text,
  sent_at                 timestamptz,
  otp_hash                text NOT NULL,          -- SHA-256 hex
  otp_expires_at          timestamptz NOT NULL,
  verify_attempts         int NOT NULL DEFAULT 0 CHECK (verify_attempts <= max_verify_attempts),
  max_verify_attempts     int NOT NULL DEFAULT 5,
  verified_at             timestamptz,
  created_at              timestamptz NOT NULL DEFAULT now(),
  updated_at              timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_sms_request_pending
  ON sms.sms_request (status, next_attempt_at)
  WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_sms_request_phone ON sms.sms_request (phone);
CREATE INDEX idx_sms_request_otp_expires ON sms.sms_request (otp_expires_at);
CREATE INDEX idx_sms_request_idempotency ON sms.sms_request (idempotency_key);

-- ============================================================
-- sms_attempt: Аудит попыток отправки
-- ============================================================
CREATE TABLE sms.sms_attempt (
  id                      bigserial PRIMARY KEY,
  sms_request_id          uuid NOT NULL REFERENCES sms.sms_request(id) ON DELETE CASCADE,
  attempt_no              int NOT NULL,
  started_at              timestamptz NOT NULL,
  finished_at             timestamptz,
  result                  varchar(16) NOT NULL CHECK (result IN ('SUCCESS','FAIL')),
  error_code              varchar(64),
  error_message           text,
  provider_message_id     text,
  latency_ms              int
);

CREATE INDEX idx_sms_attempt_request ON sms.sms_attempt (sms_request_id, attempt_no);
CREATE INDEX idx_sms_attempt_started ON sms.sms_attempt (started_at);

-- ============================================================
-- Комментарии к таблицам
-- ============================================================
COMMENT ON TABLE sms.sms_request IS 'Запросы на отправку SMS с OTP-кодом';
COMMENT ON TABLE sms.sms_attempt IS 'История попыток отправки SMS через провайдеров';

COMMENT ON COLUMN sms.sms_request.idempotency_key IS 'Ключ идемпотентности (UUID от клиента)';
COMMENT ON COLUMN sms.sms_request.otp_hash IS 'SHA-256 hex от 6-значного кода';
COMMENT ON COLUMN sms.sms_request.status IS 'IN_PROGRESS | SUCCESS | NOT_SEND';
COMMENT ON COLUMN sms.sms_request.locked_until IS 'Лиза для распределенного захвата (PostgreSQL server time)';
COMMENT ON COLUMN sms.sms_request.locked_by IS 'ID инстанса, захватившего запись';
COMMENT ON COLUMN sms.sms_attempt.latency_ms IS 'Латентность вызова провайдера в миллисекундах';
COMMENT ON COLUMN sms.sms_attempt.provider_message_id IS 'ID сообщения от провайдера (Vonage/Twilio)';