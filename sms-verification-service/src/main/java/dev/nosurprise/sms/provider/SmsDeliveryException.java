package dev.nosurprise.sms.provider;

/** Провайдер не принял SMS (ошибка Twilio или сети) → 502 SMS_DELIVERY_FAILED. */
public class SmsDeliveryException extends RuntimeException {

    public SmsDeliveryException(String message) {
        super(message);
    }

    public SmsDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}