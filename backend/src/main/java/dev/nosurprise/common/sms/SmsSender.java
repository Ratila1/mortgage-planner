package dev.nosurprise.common.sms;

/** Порт отправки SMS (MICROLOANS.md §7.4). Реальный провайдер — отдельная реализация под профилем. */
public interface SmsSender {

    void send(String phone, String text);
}
