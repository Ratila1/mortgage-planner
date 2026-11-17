package dev.nosurprise.customer.web.dto;

import dev.nosurprise.customer.kyc.KycMetadata;

import java.time.Instant;
import java.util.UUID;

/** Статус фото паспорта для ЛК: загружено или удалено по сроку (§3.13). Содержимое клиенту не отдаём. */
public record KycMetadataResponse(UUID id, String contentType, int sizeBytes, Instant uploadedAt, boolean deleted) {

    public static KycMetadataResponse from(KycMetadata metadata) {
        return new KycMetadataResponse(metadata.id(), metadata.contentType(), metadata.sizeBytes(),
                metadata.uploadedAt(), !metadata.contentAvailable());
    }
}
