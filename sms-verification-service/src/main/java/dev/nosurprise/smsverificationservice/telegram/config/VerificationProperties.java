package dev.nosurprise.smsverificationservice.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.verification")
public record VerificationProperties(Duration codeTtl, int maxAttempts) {}
