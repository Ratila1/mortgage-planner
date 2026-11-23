package dev.nosurprise.sms.provider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Провайдер SMS выбирается одним свойством {@code app.sms.provider}. Vonage/Twilio — собственные
 * {@code @Component} с {@code @ConditionalOnProperty}, здесь остаётся только логирующий транспорт:
 * его нельзя помечать компонентом, иначе он появится и при реальном провайдере.
 */
@Configuration
@EnableConfigurationProperties({VonageProperties.class, TwilioProperties.class})
public class SmsConfig {

    @Bean
    @ConditionalOnProperty(name = "app.sms.provider", havingValue = "log")
    @Qualifier("smsTransport")
    SmsSender loggingSmsSender() {
        return new LoggingSmsSender();
    }
}
