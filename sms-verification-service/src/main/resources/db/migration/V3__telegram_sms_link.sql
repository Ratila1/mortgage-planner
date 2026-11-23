-- Links a Telegram verification to the SMS request that must be delivered first.
-- The Telegram code is sent only after that SMS request reaches SUCCESS.
ALTER TABLE sms.verification_requests
    ADD COLUMN sms_id uuid;

CREATE UNIQUE INDEX idx_verification_requests_sms_id
    ON sms.verification_requests (sms_id)
    WHERE sms_id IS NOT NULL;

COMMENT ON COLUMN sms.verification_requests.sms_id IS
    'SMS request id; the shared code is sent to Telegram after this request is delivered';
