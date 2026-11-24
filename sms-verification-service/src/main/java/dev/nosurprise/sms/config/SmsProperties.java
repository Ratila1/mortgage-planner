package dev.nosurprise.sms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "sms")
public record SmsProperties(
    List<String> cisPrefixes,
    int maxAttempts,
    Duration firstBackoff,
    Duration secondBackoff,
    Duration otpTtl,
    int maxVerifyAttempts,
    double simulatedSuccessRate,
    int dispatchBatchSize,
    Duration lockLease,
    Duration retentionRequest,
    RateLimit rateLimit
) {
    /** Лимит по номеру; IP-лимит не нужен: POST /api/v1/sms доступен только с service-JWT. */
    public record RateLimit(int phonePerMinute) {}
}
