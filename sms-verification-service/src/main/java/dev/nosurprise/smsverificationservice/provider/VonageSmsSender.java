package dev.nosurprise.smsverificationservice.provider;

import com.vonage.client.VonageClient;
import com.vonage.client.messages.MessageResponse;
import com.vonage.client.messages.sms.SmsTextRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class VonageSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(VonageSmsSender.class);

    private final VonageClient client;
    private final VonageProperties properties;

    public VonageSmsSender(VonageProperties properties) {
        this.properties = properties;
        properties.validate();
        this.client = VonageClient.builder()
            .apiKey(properties.apiKey())
            .apiSecret(properties.apiSecret())
            .build();
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
            String reason = maskDigits(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            log.warn("SMS to {} failed: {}", PhoneMask.mask(phone), reason);
            throw new SmsDeliveryException("vonage rejected SMS: " + reason, ex);
        }
    }

    static String maskDigits(String value) {
        return value == null ? null : value.replaceAll("\\+?\\d{7,}", "***");
    }
}