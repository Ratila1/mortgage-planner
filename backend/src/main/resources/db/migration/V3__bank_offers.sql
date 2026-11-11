-- V3: банковские предложения (§6.1).
CREATE TABLE bank_offers (
    id                       uuid PRIMARY KEY,
    bank_name                varchar(255) NOT NULL,
    rate                     numeric(5,2) NOT NULL,
    min_down_payment_percent numeric(5,2) NOT NULL,
    max_term_months          integer NOT NULL,
    active                   boolean NOT NULL DEFAULT true,
    updated_at               timestamptz NOT NULL DEFAULT now(),
    version                  bigint NOT NULL DEFAULT 0
);