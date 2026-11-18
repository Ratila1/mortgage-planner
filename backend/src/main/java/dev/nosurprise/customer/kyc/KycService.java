package dev.nosurprise.customer.kyc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Фото паспорта (MICROLOANS.md §3.11): загрузка, метаданные, выдача админу, удаление содержимого по сроку.
 * Загрузка — отдельный запрос со своей транзакцией; заявка только ссылается на уже сохранённый документ (§4.8).
 */
@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final KycDocumentRepository repository;
    private final KycProperties properties;
    private final Clock clock;

    public KycService(KycDocumentRepository repository, KycProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public KycMetadata uploadPassport(UUID userId, byte[] content) {
        String contentType = KycFileValidator.detectContentType(content, properties.maxFileSize().toBytes());
        KycDocument document = new KycDocument(userId, content, contentType, sha256(content), clock.instant());
        repository.save(document);
        return repository.findMetadataById(document.getId()).orElseThrow();
    }

    /** Последнее загруженное фото пользователя (может быть уже удалено по сроку — см. contentAvailable). */
    @Transactional(readOnly = true)
    public Optional<KycMetadata> latestPassport(UUID userId) {
        return repository.findMetadataByUser(userId, PageRequest.of(0, 1)).stream().findFirst();
    }

    /** Фото, на которое можно сослаться в новой заявке: последнее и не удалённое, иначе KycRequiredException. */
    @Transactional(readOnly = true)
    public KycMetadata requireUsablePassport(UUID userId) {
        return latestPassport(userId).filter(KycMetadata::contentAvailable).orElseThrow(KycRequiredException::new);
    }

    /**
     * Содержимое для админа. Просмотр пишется в лог приложения (кто, что, откуда), отдельной таблицы нет (§5).
     */
    @Transactional(readOnly = true)
    public KycContent loadForAdmin(UUID documentId, UUID adminId, String ip) {
        KycDocument document = repository.findById(documentId).orElseThrow(KycNotFoundException::new);
        if (document.getContent() == null) {
            throw new KycNotFoundException();
        }
        log.info("kyc document viewed: documentId={}, adminId={}, ip={}", documentId, adminId, ip);
        return new KycContent(document.getContent(), document.getContentType());
    }

    /** Удаляет содержимое документов (метаданные остаются). Какие документы — решает модуль loan (§3.11). */
    @Transactional
    public int purgeContent(Collection<UUID> documentIds) {
        if (documentIds.isEmpty()) {
            return 0;
        }
        return repository.purgeContent(documentIds, clock.instant());
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
