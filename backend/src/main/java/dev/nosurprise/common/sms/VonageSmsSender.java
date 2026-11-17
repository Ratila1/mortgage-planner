package dev.nosurprise.common.sms;

import com.vonage.client.VonageClient;
import com.vonage.client.messages.MessageResponse;
import com.vonage.client.messages.sms.SmsTextRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Отправка SMS через Vonage Messages API (SDK com.vonage:server-sdk).
 * Vonage ждёт номер в международном формате без «+»: профиль хранит +375291234567, отправляем 375291234567.
 * Текст и номер в лог не пишем: в тексте код, номер — персональные данные (§5).
 */
public class VonageSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(VonageSmsSender.class);

    private final VonageClient client;
    private final VonageProperties properties;

    public VonageSmsSender(VonageClient client, VonageProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void send(String phone, String text) {
        String to = phone.startsWith("+") ? phone.substring(1) : phone;
        try {
            MessageResponse response = client.getMessagesClient().sendMessage(SmsTextRequest.builder()
                    .from(properties.from())
                    .to(to)
                    .text(text)
                    .build());
            log.info("SMS accepted by vonage to {}: messageUuid={}", PhoneMask.mask(phone), response.getMessageUuid());
        } catch (RuntimeException ex) {
            // Ошибки SDK бывают с номером внутри — длинные цепочки цифр в логе маскируем.
            String reason = maskDigits(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("SMS to {} failed: {}", PhoneMask.mask(phone), reason);
            throw new SmsDeliveryException("vonage rejected SMS: " + reason, ex);
        }
    }

    static String maskDigits(String value) {
        return value == null ? null : value.replaceAll("\\+?\\d{7,}", "***");
    }
}
