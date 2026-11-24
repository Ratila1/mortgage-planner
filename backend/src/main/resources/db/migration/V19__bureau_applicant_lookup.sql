-- V19: перенумерованная миграция credit-bureau (ранее V16).
-- VIEW bureau_applicant_lookup и индексы создаются/обновляются в V17__credit_policy.sql,
-- поэтому здесь остаётся no-op, чтобы не дублировать объекты и не ломать Flyway.
SELECT 1;
