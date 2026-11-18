package dev.nosurprise.customer.kyc;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Настройки KYC (app.kyc.*). retention — сколько хранить фото после отказа, отмены или истечения заявки (§3.11).
 */
@ConfigurationProperties(prefix = "app.kyc")
public record KycProperties(DataSize maxFileSize, Duration retention) {

    public KycProperties {
        if (maxFileSize == null) {
            maxFileSize = DataSize.ofMegabytes(5);
        }
        if (retention == null) {
            retention = Duration.ofDays(7);
        }
    }
}
