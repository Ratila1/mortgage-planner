-- V11: профиль клиента (MICROLOANS.md §4.2). Связь 1:1 с users через общий ключ.
-- Email здесь не хранится: единственный источник — users.email.
CREATE TABLE customer_profile (
    user_id           uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    last_name         varchar(100)  NOT NULL,
    first_name        varchar(100)  NOT NULL,
    middle_name       varchar(100),
    phone             varchar(20)   NOT NULL,
    phone_verified_at timestamptz,
    monthly_income    numeric(12,2) NOT NULL CHECK (monthly_income > 0),
    created_at        timestamptz   NOT NULL DEFAULT now(),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    version           bigint        NOT NULL DEFAULT 0
);

CREATE INDEX idx_customer_profile_names ON customer_profile (lower(last_name), lower(first_name));
