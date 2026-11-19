package dev.nosurprise.smsverificationservice.telegram.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TelegramProperties.class, VerificationProperties.class})
public class TelegramPropertiesConfig {}
