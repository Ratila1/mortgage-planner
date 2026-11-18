package dev.nosurprise.smsverificationservice.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Configuration for the simulated SMS sender, only active in "sim" profile.
 * This avoids bean definition conflicts with SmsConfig which handles vonage/twilio in "prod" profile.
 */
@Configuration
@Profile("sim")
public class LoggingSmsSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Bean
    @Profile("sim")
    public LoggingSmsSender loggingSmsSender() {
        return new LoggingSmsSender();
    }
}