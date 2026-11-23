package dev.nosurprise.sms.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "twilio")
@Qualifier("smsTransport")
public class TwilioSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final TwilioProperties properties;

    public TwilioSmsSender(RestClient.Builder builder, TwilioProperties properties) {
        this.properties = properties;
        properties.validate();
        // Таймауты задаёт сам транспорт: он больше не собирается в SmsConfig.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        this.client = builder
            .requestFactory(requestFactory)
            .baseUrl(properties.baseUrl())
            .defaultHeaders(headers -> headers.setBasicAuth(properties.username(), properties.authSecret(),
                    StandardCharsets.UTF_8))
            .build();
    }

    @Override
    public SendResult send(String phone, String text) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", phone);
        if (TwilioProperties.blank(properties.messagingServiceSid())) {
            form.add("From", properties.from());
        } else {
            form.add("MessagingServiceSid", properties.messagingServiceSid());
        }
        form.add("Body", text);
        try {
            Map<?, ?> response = client.post()
                .uri("/2010-04-01/Accounts/{accountSid}/Messages.json", properties.accountSid())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, reply) -> {
                    throw new SmsDeliveryException("twilio rejected SMS: HTTP " + reply.getStatusCode().value()
                        + ", twilio code " + twilioCode(reply.getBody().readAllBytes()));
                })
                .body(Map.class);
            String sid = response == null ? null : String.valueOf(response.get("sid"));
            log.info("SMS accepted by twilio to {}: sid={}, status={}", PhoneMask.mask(phone), sid,
                response == null ? null : response.get("status"));
            return SendResult.success();
        } catch (SmsDeliveryException ex) {
            log.warn("SMS to {} failed: {}", PhoneMask.mask(phone), ex.getMessage());
            throw ex;
        } catch (RestClientException ex) {
            log.warn("SMS to {} failed: twilio unreachable", PhoneMask.mask(phone), ex);
            throw new SmsDeliveryException("twilio is unreachable", ex);
        }
    }

    private static String twilioCode(byte[] body) {
        try {
            JsonNode code = JSON.readTree(body).get("code");
            return code == null || code.isNull() ? "unknown" : code.asString();
        } catch (RuntimeException ex) {
            return "unknown";
        }
    }
}
