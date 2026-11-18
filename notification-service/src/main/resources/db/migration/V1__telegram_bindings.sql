-- V1: привязка Telegram к пользователю (источник истины — этот сервис).
CREATE TABLE telegram_bindings (
    user_id     uuid PRIMARY KEY,
    phone       varchar(20)  NOT NULL,
    telegram_id bigint       NOT NULL UNIQUE,
    username    varchar(64),
    linked_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);

CREATE INDEX idx_telegram_bindings_phone ON telegram_bindings (phone);