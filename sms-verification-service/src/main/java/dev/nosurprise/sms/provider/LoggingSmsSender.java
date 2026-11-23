package dev.nosurprise.sms.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Провайдер, который ничего не отправляет, а пишет факт отправки в лог (prod + app.sms.provider=log).
 */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public SendResult send(String phone, String text) {
        // Тело не логируем: в нём OTP, а логи живут дольше кода.
        log.info("SMS stub to {}: body contains OTP, not logged ({} chars)", PhoneMask.mask(phone), text.length());
        return SendResult.success();
    }
}
