-- V17: скоринг и просрочка (CREDIT_POLICY.md, часть A §4).

-- Профиль: данные для скоринга. Nullable — у старых профилей их нет; подать заявку без них нельзя
-- (409 PROFILE_INCOMPLETE), форма профиля требует их обязательно.
ALTER TABLE customer_profile
    ADD COLUMN birth_date date,
    ADD COLUMN sex        varchar(8) CHECK (sex IN ('MALE', 'FEMALE')),
    ADD COLUMN country    varchar(2) CHECK (country ~ '^[A-Z]{2}$'),
    ADD COLUMN city       varchar(100);

-- Тип займа ссылается на цель из справочника credit-policy; балл цели живёт там.
ALTER TABLE loan_types ADD COLUMN purpose_code varchar(32) CHECK (purpose_code ~ '^[A-Z][A-Z0-9_]{1,31}$');
UPDATE loan_types SET purpose_code = 'TECH'   WHERE id = 'a1111111-1111-1111-1111-111111111111';
UPDATE loan_types SET purpose_code = 'PAYDAY' WHERE id = 'a2222222-2222-2222-2222-222222222222';

-- Заявка: статус OVERDUE, дата возврата и фактическая дата оплаты (§5).
ALTER TABLE loan_applications DROP CONSTRAINT loan_applications_status_check;
ALTER TABLE loan_applications ADD CONSTRAINT loan_applications_status_check CHECK (status IN
    ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'ACTIVE', 'OVERDUE', 'EXPIRED', 'REPAID'));

ALTER TABLE loan_applications
    ADD COLUMN due_date date,
    ADD COLUMN paid_at  date;

-- Итог скоринга (§6). Расшифровка по факторам и вход расчёта — в credit-policy.
ALTER TABLE loan_applications
    ADD COLUMN scoring_request_id   uuid,
    ADD COLUMN scoring_status       varchar(16) CHECK (scoring_status IN ('PENDING', 'COMPLETED', 'NOT_CALCULATED')),
    ADD COLUMN scoring_requested_at timestamptz,
    ADD COLUMN scoring_completed_at timestamptz,
    ADD COLUMN score                numeric(5,4),
    ADD COLUMN score_points         integer,
    ADD COLUMN rating_class         varchar(2),
    ADD COLUMN pd                   numeric(7,6),
    ADD COLUMN scoring_decision     varchar(16) CHECK (scoring_decision IN ('RECOMMEND', 'REVIEW', 'AUTO_REJECT')),
    ADD COLUMN rules_version        integer;

-- Одна незакрытая заявка на клиента: должник (OVERDUE) новую не подаст.
DROP INDEX uq_loan_applications_one_active;
CREATE UNIQUE INDEX uq_loan_applications_one_active
    ON loan_applications (user_id) WHERE status IN ('SUBMITTED', 'APPROVED', 'ACTIVE', 'OVERDUE');

-- Ночная задача просрочки ищет ACTIVE с прошедшей датой возврата.
CREATE INDEX idx_loan_applications_due ON loan_applications (due_date) WHERE status = 'ACTIVE';

-- Напоминания о просрочке на 3 и 7 день пишутся в журнал: по ним задача не шлёт напоминание дважды.
ALTER TABLE loan_events DROP CONSTRAINT loan_events_type_check;
ALTER TABLE loan_events ADD CONSTRAINT loan_events_type_check CHECK (type IN
    ('STATUS_CHANGED', 'OFFER_NOT_SIGNED', 'SIGN_CODE_SENT', 'SIGN_CODE_FAILED', 'SIGN_CODE_BURNED',
     'SIGN_BLOCKED_PHONE_NOT_VERIFIED', 'OVERDUE_REMINDER_SENT'));

-- Бюро видит просрочку (§10). Колонки VIEW не меняются; индексы — с тем же условием, что у VIEW.
CREATE OR REPLACE VIEW bureau_applicant_lookup AS
SELECT a.user_id,
       a.id                                                            AS application_id,
       a.applicant_phone                                               AS phone,
       replace(lower(a.applicant_last_name), 'ё', 'е')                 AS last_name_norm,
       replace(lower(a.applicant_first_name), 'ё', 'е')                AS first_name_norm,
       replace(lower(coalesce(a.applicant_middle_name, '')), 'ё', 'е') AS middle_name_norm
FROM loan_applications a
WHERE a.status IN ('ACTIVE', 'OVERDUE', 'REPAID');

DROP INDEX idx_loan_applications_applicant_phone;
DROP INDEX idx_loan_applications_applicant_names;
CREATE INDEX idx_loan_applications_applicant_phone ON loan_applications (applicant_phone)
    WHERE status IN ('ACTIVE', 'OVERDUE', 'REPAID');
CREATE INDEX idx_loan_applications_applicant_names ON loan_applications
    (replace(lower(applicant_last_name), 'ё', 'е'), replace(lower(applicant_first_name), 'ё', 'е'))
    WHERE status IN ('ACTIVE', 'OVERDUE', 'REPAID');
