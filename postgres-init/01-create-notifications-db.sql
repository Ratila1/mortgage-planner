-- Создание отдельной БД для notification-service в том же кластере Postgres.
SELECT 'CREATE DATABASE nosurprise_notifications'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'nosurprise_notifications')\gexec