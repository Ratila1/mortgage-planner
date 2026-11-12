-- V2: пользователи и роли (§6.1).
-- Refresh-токены хранятся только в Redis (refresh:{userId}:{sha256(token)}) — таблицы refresh_tokens нет.
-- id = uuid: генерируется приложением (не нужен round-trip в БД), неугадываем, кладётся строкой
-- в JWT-subject и события RabbitMQ. provider = varchar + CHECK: расширяется миграцией,
-- а не ALTER TYPE, и мапится Hibernate @Enumerated(STRING) без кастомных типов.

CREATE TABLE users (
    id             uuid PRIMARY KEY,
    email          varchar(255) NOT NULL UNIQUE,
    password_hash  varchar(255),                    -- null для OAuth-провайдеров
    email_verified boolean NOT NULL DEFAULT false,
    provider       varchar(16)  NOT NULL CHECK (provider IN ('LOCAL', 'GOOGLE', 'GITHUB')),
    provider_id    varchar(255),
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    varchar(16) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);
