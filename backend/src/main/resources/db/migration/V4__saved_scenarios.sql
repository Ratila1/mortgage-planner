-- V4: сохранённые сценарии (§6.1).
CREATE TABLE saved_scenarios (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       varchar(255) NOT NULL,
    params     jsonb NOT NULL,
    summary    jsonb NOT NULL,
    calc_id    varchar(24) NOT NULL,
    offer_id   uuid REFERENCES bank_offers(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_saved_scenarios_user_created
    ON saved_scenarios (user_id, created_at DESC);

CREATE INDEX idx_saved_scenarios_offer
    ON saved_scenarios (offer_id);