package dev.nosurprise.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Системный Clock (время UTC, §9): единая точка для сервисов, которым нужен момент времени
 * (JWT issue/expire). В тестах подставляется фиксированный Clock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
