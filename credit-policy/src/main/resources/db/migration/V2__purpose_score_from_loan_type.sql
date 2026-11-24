-- V2: справочник целей убран (CREDIT_POLICY.md §7.3). Цель займа — сам тип займа в монолите: его балл цели
-- (loan_types.purpose_score) приходит в событии loan.scoring.requested и сохраняется в input расчёта.
DROP TABLE purposes;
