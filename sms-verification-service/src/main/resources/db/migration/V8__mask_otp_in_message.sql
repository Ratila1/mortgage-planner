-- V8: убрать plaintext OTP из sms_request.message.
-- Код теперь хранится только в Redis (handoff/challenge) на время TTL, а сообщение для отправки
-- рендерится при dispatch. Старые значения затираются, чтобы бэкапы/дампы не содержали OTP.

UPDATE sms.sms_request
SET message = CASE
    WHEN language = 'RU' THEN 'Код подтверждения: ******'
    ELSE 'Verification code: ******'
END
WHERE message IS DISTINCT FROM CASE
    WHEN language = 'RU' THEN 'Код подтверждения: ******'
    ELSE 'Verification code: ******'
END;

COMMENT ON COLUMN sms.sms_request.message IS
    'Редактированный шаблон сообщения без OTP; реальный текст рендерится при dispatch из Redis-handoff';
