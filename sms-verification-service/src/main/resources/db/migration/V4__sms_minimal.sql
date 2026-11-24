-- V4: минимальный набор колонок sms_request + упрощённая sms_attempt.
-- Сокращение «лишних» колонок после миграции на единую OTP-модель (CREATED/DELIVERED/NOT_DELIVERED/VERIFIED/EXPIRED).

ALTER TABLE sms.sms_request
    DROP COLUMN IF EXISTS max_attempts,
    DROP COLUMN IF EXISTS next_attempt_at,
    DROP COLUMN IF EXISTS provider_message_id,
    DROP COLUMN IF EXISTS last_error,
    DROP COLUMN IF EXISTS locked_until,
    DROP COLUMN IF EXISTS locked_by,
    DROP COLUMN IF EXISTS otp_hash,
    DROP COLUMN IF EXISTS otp_expires_at,
    DROP COLUMN IF EXISTS verify_attempts,
    DROP COLUMN IF EXISTS max_verify_attempts,
    DROP COLUMN IF EXISTS verified_at,
    DROP COLUMN IF EXISTS updated_at;

-- created_at остаётся: он — якорь retention-джобы.

-- Удаляем старый CHECK-констрейт (у inline CHECK в V1 имени нет, ищем по таблице/колонке).
DO $$
DECLARE
    cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'sms'
      AND t.relname = 'sms_request'
      AND c.contype = 'c'
      AND c.conname LIKE '%status%';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sms.sms_request DROP CONSTRAINT %I', cname);
    END IF;
END $$;

-- legacy-статусы существующих строк переносим в новый набор.
UPDATE sms.sms_request SET status = 'CREATED' WHERE status = 'IN_PROGRESS';
UPDATE sms.sms_request SET status = 'DELIVERED' WHERE status = 'SUCCESS';
UPDATE sms.sms_request SET status = 'NOT_DELIVERED' WHERE status = 'NOT_SEND';

-- status: новый набор значений.
ALTER TABLE sms.sms_request
    ADD CONSTRAINT sms_request_status_check CHECK (status IN ('CREATED','DELIVERED','NOT_DELIVERED','VERIFIED','EXPIRED'));

DROP INDEX IF EXISTS idx_sms_request_pending;
CREATE INDEX IF NOT EXISTS idx_sms_request_pending ON sms.sms_request (status) WHERE status = 'CREATED';
DROP INDEX IF EXISTS idx_sms_request_otp_expires;

-- sms_attempt: только факт попытки (sms_request_id, attempt_no, result).
ALTER TABLE sms.sms_attempt
    DROP COLUMN IF EXISTS started_at,
    DROP COLUMN IF EXISTS finished_at,
    DROP COLUMN IF EXISTS error_code,
    DROP COLUMN IF EXISTS error_message,
    DROP COLUMN IF EXISTS provider_message_id,
    DROP COLUMN IF EXISTS latency_ms;

COMMENT ON TABLE sms.sms_request IS 'OTP-запросы: создание, доставка, подтверждение';
COMMENT ON TABLE sms.sms_attempt IS 'Факт отправленных попыток (sms_request_id, attempt_no, result)';