package dev.nosurprise.smsverificationservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Random;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SmsProperties.class)
public class SchedulerConfig {

    @Bean("providerRandom")
    public Random providerRandom() {
        return new Random();
    }

    @Bean
    public TransactionTemplate txTemplate(org.springframework.transaction.PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}