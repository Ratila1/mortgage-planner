package dev.nosurprise.sms.provider;

/**
 * Единственный порт отправки SMS. Исход возвращается, а не теряется в исключении: dispatch-джобе нужно
 * знать, чем кончилась попытка, чтобы записать её и решить судьбу Telegram-канала.
 */
public interface SmsSender {

    SendResult send(String phone, String text);

    record SendResult(boolean ok) {
        public static SendResult success() { return new SendResult(true); }
        public static SendResult failure() { return new SendResult(false); }
    }
}
