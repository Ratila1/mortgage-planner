-- V5: обновить CHECK-констрейт verification_requests под новый SmsStatus enum.
-- Старый набор: PENDING, SENT, APPROVED, REJECTED, EXPIRED
-- Новый набор: CREATED, DELIVERED, NOT_DELIVERED, VERIFIED, EXPIRED

DO $$
DECLARE
    cname text;
BEGIN
    SELECT conname INTO cname
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = 'sms'
      AND t.relname = 'verification_requests'
      AND c.contype = 'c'
      AND c.conname LIKE '%status%';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE sms.verification_requests DROP CONSTRAINT %I', cname);
    END IF;
END $$;

-- Переносим legacy-статусы на новые (если есть данные)
UPDATE sms.verification_requests SET status = 'CREATED' WHERE status = 'PENDING';
UPDATE sms.verification_requests SET status = 'DELIVERED' WHERE status = 'SENT';
UPDATE sms.verification_requests SET status = 'VERIFIED' WHERE status = 'APPROVED';
UPDATE sms.verification_requests SET status = 'EXPIRED' WHERE status = 'REJECTED';

-- Новый констрейт
ALTER TABLE sms.verification_requests
    ADD CONSTRAINT verification_requests_status_check CHECK (status IN ('CREATED','DELIVERED','NOT_DELIVERED','VERIFIED','EXPIRED'));