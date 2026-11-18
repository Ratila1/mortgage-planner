package dev.nosurprise.common.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Заглушка SMS для локальной разработки и тестов: пишет текст в лог приложения.
 * Включается при app.sms.provider=log (по умолчанию), см. {@link SmsConfig}.
 */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String phone, String text) {
        // Текст с кодом в логе — смысл заглушки: так разработчик видит код. Номер маскируем (§5).
        log.info("SMS stub to {}: {}", PhoneMask.mask(phone), text);
    }
}
