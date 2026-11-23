-- V7: снять неиспользуемые объекты схемы, оставшиеся от первоначального дизайна.
-- Последовательность нигде не использовалась: id SMS генерирует Hibernate (UUID).
DROP SEQUENCE IF EXISTS sms.sms_attempt_seq;

-- Дубли и индексы под запросы, которых нет: Idempotency-Key уже закрыт UNIQUE-констрейнтом,
-- выборка по phone не делается, partial-индекс по константному status не ускоряет claim
-- (claim идёт по created_at, его закрывает idx_sms_request_claim), а verification_requests
-- читается только по PK и по sms_id.
DROP INDEX IF EXISTS sms.idx_sms_request_idempotency;
DROP INDEX IF EXISTS sms.idx_sms_request_phone;
DROP INDEX IF EXISTS sms.idx_sms_request_pending;
DROP INDEX IF EXISTS sms.idx_verification_requests_user;
