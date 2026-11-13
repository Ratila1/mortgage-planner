-- V6: уведомления (§6.1).
CREATE TABLE notifications (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       varchar(64) NOT NULL,
    payload    jsonb NOT NULL,
    read       boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);