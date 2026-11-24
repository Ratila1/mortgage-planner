-- V18: цель займа — это сам тип займа (CREDIT_POLICY.md §3.2, §7.3). Отдельный справочник целей в credit-policy
-- убран: балл цели хранится у типа займа и уходит в credit-policy в событии loan.scoring.requested.
-- purpose_code из V17 больше не нужен.
ALTER TABLE loan_types DROP COLUMN purpose_code;

ALTER TABLE loan_types
    ADD COLUMN purpose_score numeric(3,2) NOT NULL DEFAULT 0.50 CHECK (purpose_score >= 0 AND purpose_score <= 1);

UPDATE loan_types SET purpose_score = 0.60 WHERE id = 'a1111111-1111-1111-1111-111111111111';   -- На iPhone: техника
UPDATE loan_types SET purpose_score = 0.30 WHERE id = 'a2222222-2222-2222-2222-222222222222';   -- Последний шанс: до зарплаты
