-- V6: lease для dispatch-джобы, TTL кода в БД и аудит переходов статуса.
--
-- V4 удалила locked_until/locked_by/next_attempt_at, из-за чего claimBatch не мог отличить
-- «строка в полёте» от «готова к повтору», а backoff считался от created_at. Возвращаем lease
-- в минимальном виде и якорь последней попытки.

ALTER TABLE sms.sms_request
    ADD COLUMN IF NOT EXISTS last_attempt_at timestamptz,
    ADD COLUMN IF NOT EXISTS locked_until    timestamptz,
    ADD COLUMN IF NOT EXISTS locked_by       text,
    ADD COLUMN IF NOT EXISTS expires_at      timestamptz,
    ADD COLUMN IF NOT EXISTS verified_at     timestamptz,
    ADD COLUMN IF NOT EXISTS updated_at      timestamptz NOT NULL DEFAULT now();

COMMENT ON COLUMN sms.sms_request.last_attempt_at IS
    'Время последней попытки отправки; от него считается backoff (не от created_at)';
COMMENT ON COLUMN sms.sms_request.locked_until IS
    'Lease захвата dispatch-джобой; просроченная lease позволяет другому инстансу забрать запись';
COMMENT ON COLUMN sms.sms_request.locked_by IS
    'ID инстанса, захватившего запись';
COMMENT ON COLUMN sms.sms_request.expires_at IS
    'Истечение OTP: источник истины для expiresAt в API и для перевода в EXPIRED';
COMMENT ON COLUMN sms.sms_request.verified_at IS
    'Момент успешной проверки кода (аудит)';
COMMENT ON COLUMN sms.sms_request.updated_at IS
    'Время последнего изменения статуса (аудит)';

CREATE INDEX IF NOT EXISTS idx_sms_request_claim
    ON sms.sms_request (created_at) WHERE status = 'CREATED';

CREATE INDEX IF NOT EXISTS idx_sms_request_expires
    ON sms.sms_request (expires_at) WHERE status IN ('CREATED', 'DELIVERED');
