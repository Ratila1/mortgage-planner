package dev.nosurprise.common.sms;

import com.vonage.client.VonageClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Выбор провайдера SMS по app.sms.provider: log (по умолчанию, код в лог), vonage или twilio.
 * Ровно один бин SmsSender, поэтому сервисы не знают, какой провайдер подключён.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TwilioProperties.class, VonageProperties.class})
public class SmsConfig {

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
    SmsSender loggingSmsSender() {
        return new LoggingSmsSender();
    }

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "vonage")
    SmsSender vonageSmsSender(VonageProperties properties) {
        properties.validate();
        VonageClient client = VonageClient.builder()
                .apiKey(properties.apiKey())
                .apiSecret(properties.apiSecret())
                .build();
        return new VonageSmsSender(client, properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "twilio")
    SmsSender twilioSmsSender(TwilioProperties properties) {
        properties.validate();
        // Таймауты обязательны: без них зависший Twilio держал бы поток запроса клиента.
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return new TwilioSmsSender(RestClient.builder().requestFactory(requestFactory), properties);
    }
}
