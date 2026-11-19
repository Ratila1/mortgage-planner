package dev.nosurprise.smsverificationservice.config;

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
    Duration retentionAttempt,
    RateLimit rateLimit
) {
    public record RateLimit(int phonePerMinute, int ipPerMinute) {}
}