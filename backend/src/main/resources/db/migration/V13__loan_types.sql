-- V13: типы микрозаймов (MICROLOANS.md §3.1). Валюта одна — BYN, колонки валюты нет.
CREATE TABLE loan_types (
    id            uuid PRIMARY KEY,
    name          varchar(100)  NOT NULL,
    description   varchar(1000) NOT NULL,
    min_amount    numeric(12,2) NOT NULL,
    max_amount    numeric(12,2) NOT NULL,
    min_term_days integer       NOT NULL,
    max_term_days integer       NOT NULL,
    daily_rate    numeric(6,3)  NOT NULL,
    modifier      numeric(12,2) NOT NULL DEFAULT 0,
    active        boolean       NOT NULL DEFAULT true,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    version       bigint        NOT NULL DEFAULT 0,
    CHECK (min_amount > 0 AND min_amount <= max_amount),
    CHECK (min_term_days >= 7 AND min_term_days <= max_term_days AND max_term_days <= 30),
    CHECK (daily_rate >= 0),
    CHECK (modifier >= 0)
);
