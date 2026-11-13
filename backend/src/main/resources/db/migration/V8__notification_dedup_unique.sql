-- V8: дедуп уведомлений на уровне БД (§8): повторная доставка события не создаёт дубликат,
-- даже при гонке двух consumer'ов (уникальный частичный индекс по ключу дедупа в payload).
-- Сначала чистим исторические дубли (оставляем самую свежую строку по created_at, id).
DELETE FROM notifications a
USING notifications b
WHERE a.type = b.type
  AND a.user_id = b.user_id
  AND a.payload->>'scenarioId' = b.payload->>'scenarioId'
  AND a.payload->>'calcId' = b.payload->>'calcId'
  AND (a.created_at, a.id) < (b.created_at, b.id);

CREATE UNIQUE INDEX uq_notifications_pdf_ready
    ON notifications (user_id, (payload->>'scenarioId'), (payload->>'calcId'))
    WHERE type = 'PDF_READY';

CREATE UNIQUE INDEX uq_notifications_scenario_recalc
    ON notifications (user_id, (payload->>'scenarioId'), (payload->>'calcId'))
    WHERE type = 'SCENARIO_RECALCULATED';
