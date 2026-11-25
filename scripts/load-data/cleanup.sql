-- Удаляет нагрузочные данные из Postgres (пользователи *@load.local и всё, что на них ссылается).
-- Документы в Mongo удаляет load.ps1 -Cleanup / load.sh --cleanup (по тем же детерминированным userId).
\set ON_ERROR_STOP on
\timing on
BEGIN;
CREATE TEMP TABLE load_users ON COMMIT DROP AS SELECT id FROM users WHERE email LIKE '%@load.local';
CREATE TEMP TABLE load_app_ids ON COMMIT DROP AS
    SELECT a.id FROM loan_applications a JOIN load_users u ON u.id = a.user_id;
-- Ещё не отправленные события этих заявок — иначе consumer пересоберёт документы уже удалённых заявок.
DELETE FROM outbox_events o USING load_app_ids a
 WHERE o.aggregate_type = 'loan_application' AND o.aggregate_id = a.id::text;
DELETE FROM loan_signatures s USING load_app_ids a WHERE s.application_id = a.id;
DELETE FROM loan_documents d USING load_app_ids a WHERE d.application_id = a.id;
DELETE FROM loan_events e USING load_app_ids a WHERE e.application_id = a.id;
DELETE FROM loan_applications l USING load_app_ids a WHERE l.id = a.id;
DELETE FROM users u USING load_users l WHERE u.id = l.id;   -- профили, паспорта, роли — ON DELETE CASCADE
COMMIT;
VACUUM ANALYZE loan_applications;
VACUUM ANALYZE loan_events;
