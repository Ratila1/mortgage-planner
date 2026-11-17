package dev.nosurprise.customer.kyc;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    /** Проекция без content: findById загрузил бы весь файл. */
    @Query("select new dev.nosurprise.customer.kyc.KycMetadata(d.id, d.userId, d.type, d.contentType, d.sizeBytes, "
            + "d.sha256, d.uploadedAt, d.deletedAt) from KycDocument d where d.userId = :userId "
            + "order by d.uploadedAt desc")
    List<KycMetadata> findMetadataByUser(@Param("userId") UUID userId, Pageable pageable);

    @Query("select new dev.nosurprise.customer.kyc.KycMetadata(d.id, d.userId, d.type, d.contentType, d.sizeBytes, "
            + "d.sha256, d.uploadedAt, d.deletedAt) from KycDocument d where d.id = :id")
    Optional<KycMetadata> findMetadataById(@Param("id") UUID id);

    /** Удаляет только содержимое; строка с метаданными и хэшем остаётся для аудита (§3.11). */
    @Modifying
    @Query("update KycDocument d set d.content = null, d.deletedAt = :now where d.id in :ids and d.deletedAt is null")
    int purgeContent(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);
}
