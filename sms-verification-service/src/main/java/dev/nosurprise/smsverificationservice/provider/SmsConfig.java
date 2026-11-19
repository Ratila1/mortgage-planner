package dev.nosurprise.smsverificationservice.provider;

import com.vonage.client.VonageClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Выбор провайдера SMS по app.sms.provider: log (по умолчанию), vonage или twilio.
 * В профиле sim используется SimulatedSmsProvider (через DispatchSmsProvider).
 * В профиле prod: Vonage/Twilio/Logging в зависимости от app.sms.provider.
 */
@Configuration
@EnableConfigurationProperties({VonageProperties.class, TwilioProperties.class})
public class SmsConfig {

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "vonage")
    @Profile("prod")
    SmsSender vonageSmsSender(VonageProperties properties) {
        properties.validate();
        VonageClient client = VonageClient.builder()
            .apiKey(properties.apiKey())
            .apiSecret(properties.apiSecret())
            .build();
        return new VonageSmsSender(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "twilio")
    @Profile("prod")
    SmsSender twilioSmsSender(TwilioProperties properties) {
        properties.validate();
        var httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        return new TwilioSmsSender(RestClient.builder().requestFactory(requestFactory), properties);
    }
}