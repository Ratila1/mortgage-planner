package dev.nosurprise.smsverificationservice.provider;

/**
 * Интерфейс провайдера для dispatch job. Возвращает SendResult вместо void.
 */
public interface DispatchSmsProvider {

    SendResult send(String phone, String text);

    record SendResult(boolean success, String providerMessageId, String errorCode) {
        public static SendResult success(String id) { return new SendResult(true, id, null); }
        public static SendResult failure(String err) { return new SendResult(false, null, err); }
    }
}