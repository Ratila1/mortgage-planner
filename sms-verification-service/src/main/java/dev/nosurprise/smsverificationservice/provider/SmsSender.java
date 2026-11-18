package dev.nosurprise.smsverificationservice.provider;

/** Порт отправки SMS. Реальный провайдер — отдельная реализация под профилем. */
public interface SmsSender {

    void send(String phone, String text);
}