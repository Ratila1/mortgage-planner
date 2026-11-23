package dev.nosurprise.sms.domain;

/**
 * Жизненный цикл OTP-запроса (sms_request) и telegram-верификации (sms.verification_requests).
 * CREATED — создан, ждёт отправку; DELIVERED — код доставлен (SMS провайдеру/в Telegram); NOT_DELIVERED —
 * доставка окончательно не удалась; VERIFIED — код подтверждён; EXPIRED — код сгорел или срок истёк.
 */
public enum SmsStatus {
    CREATED,
    DELIVERED,
    NOT_DELIVERED,
    VERIFIED,
    EXPIRED
}
