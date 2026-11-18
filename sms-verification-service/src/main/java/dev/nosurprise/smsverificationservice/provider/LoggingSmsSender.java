package dev.nosurprise.smsverificationservice.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple implementation of SmsSender that logs SMS messages instead of sending them.
 * Used in "sim" profile for testing/development.
 */
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String phone, String text) {
        log.info("SMS stub to {}: {}", PhoneMask.mask(phone), text);
    }
}