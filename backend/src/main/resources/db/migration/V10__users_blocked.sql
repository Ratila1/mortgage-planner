-- V10: блокировка пользователя админом (MICROLOANS.md §3.5).
ALTER TABLE users ADD COLUMN blocked boolean NOT NULL DEFAULT false;
