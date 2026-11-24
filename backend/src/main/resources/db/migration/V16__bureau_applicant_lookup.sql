-- V16: контракт для сервиса credit-bureau (CREDIT_BUREAU.md §4): поиск заёмщика по снимкам заявок.
-- Бюро читает только этот VIEW и только на чтение; таблицы монолита ему не видны.
-- Только займы — ACTIVE и REPAID (§3.1.1): неподписанные заявки с чужими данными в поиск не попадают.
-- Поменял VIEW — поправь бюро (credit-bureau, ApplicantLookup).
CREATE VIEW bureau_applicant_lookup AS
SELECT a.user_id,
       a.id                                                            AS application_id,
       a.applicant_phone                                               AS phone,
       replace(lower(a.applicant_last_name), 'ё', 'е')                 AS last_name_norm,
       replace(lower(a.applicant_first_name), 'ё', 'е')                AS first_name_norm,
       replace(lower(coalesce(a.applicant_middle_name, '')), 'ё', 'е') AS middle_name_norm
FROM loan_applications a
WHERE a.status IN ('ACTIVE', 'REPAID');

-- Поиск по телефону и по ФИО: без индексов это полный проход по всем заявкам на каждый запрос банка.
-- Частичные индексы с тем же условием, что во VIEW: в них только займы. Выражения совпадают с VIEW символ в символ,
-- иначе Postgres индекс не использует.
CREATE INDEX idx_loan_applications_applicant_phone ON loan_applications (applicant_phone)
    WHERE status IN ('ACTIVE', 'REPAID');
CREATE INDEX idx_loan_applications_applicant_names ON loan_applications
    (replace(lower(applicant_last_name), 'ё', 'е'), replace(lower(applicant_first_name), 'ё', 'е'))
    WHERE status IN ('ACTIVE', 'REPAID');
