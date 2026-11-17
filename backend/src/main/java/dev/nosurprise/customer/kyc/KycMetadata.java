package dev.nosurprise.customer.kyc;

import java.time.Instant;
import java.util.UUID;

/** Метаданные KYC-документа без содержимого: для профиля, списков и проверок при подаче. */
public record KycMetadata(UUID id, UUID userId, String type, String contentType, int sizeBytes, String sha256,
                          Instant uploadedAt, Instant deletedAt) {

    public boolean contentAvailable() {
        return deletedAt == null;
    }
}
