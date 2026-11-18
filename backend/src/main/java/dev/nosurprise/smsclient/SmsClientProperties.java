package dev.nosurprise.smsclient;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.sms-client")
public record SmsClientProperties(
    String baseUrl,
    String serviceJwtSecret,
    Duration connectTimeout,
    Duration readTimeout,
    CircuitBreakerConfig circuitBreaker
) {
    public record CircuitBreakerConfig(
        boolean enabled,
        int failureRateThreshold,
        int slidingWindowSize,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState
    ) {}
}