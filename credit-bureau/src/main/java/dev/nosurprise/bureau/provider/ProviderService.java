package dev.nosurprise.bureau.provider;

import dev.nosurprise.bureau.common.ApiException;
import dev.nosurprise.bureau.common.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Провайдеры и их ключи (CREDIT_BUREAU.md §6, §9): заводит и меняет только админ бюро.
 * Аутентификация запроса — {@link #authenticate}: ключ найден по префиксу, хеш совпал, ключ не отозван.
 */
@Service
public class ProviderService {

    private final ProviderRepository providers;
    private final ProviderKeyRepository keys;
    private final Clock clock;

    public ProviderService(ProviderRepository providers, ProviderKeyRepository keys, Clock clock) {
        this.providers = providers;
        this.keys = keys;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Provider> list() {
        return providers.findAllByOrderByCreatedAtAsc();
    }

    @Transactional(readOnly = true)
    public Provider get(UUID id) {
        return providers.findById(id).orElseThrow(ProviderService::providerNotFound);
    }

    @Transactional(readOnly = true)
    public List<ProviderKey> keysOf(UUID providerId) {
        return keys.findByProviderIdOrderByCreatedAtAsc(providerId);
    }

    @Transactional
    public Provider create(String name, boolean sandbox, int requestsPerMinute, int requestsPerDay) {
        String trimmed = name.trim();
        if (providers.existsByName(trimmed)) {
            throw nameTaken();
        }
        try {
            return providers.saveAndFlush(new Provider(trimmed, sandbox, requestsPerMinute, requestsPerDay,
                    clock.instant()));
        } catch (DataIntegrityViolationException ex) {
            // Параллельное создание с тем же именем: сработал UNIQUE (V1).
            throw nameTaken();
        }
    }

    @Transactional
    public Provider update(UUID id, String name, int requestsPerMinute, int requestsPerDay, long version) {
        Provider provider = get(id);
        if (!Objects.equals(provider.getVersion(), version)) {
            throw new ObjectOptimisticLockingFailureException(Provider.class, id);
        }
        String trimmed = name.trim();
        if (providers.existsByNameAndIdNot(trimmed, id)) {
            throw nameTaken();
        }
        provider.update(trimmed, requestsPerMinute, requestsPerDay, clock.instant());
        return providers.saveAndFlush(provider);
    }

    /** Приостановка действует сразу: статус проверяется на каждом запросе. */
    @Transactional
    public Provider changeStatus(UUID id, ProviderStatus status) {
        Provider provider = get(id);
        provider.changeStatus(status, clock.instant());
        return providers.save(provider);
    }

    /** Новый ключ. Полный текст возвращается один раз — потом его нельзя получить, только выпустить новый. */
    @Transactional
    public IssuedKey issueKey(UUID providerId) {
        get(providerId);
        ApiKeys.Generated generated = ApiKeys.generate();
        ProviderKey key = keys.save(new ProviderKey(providerId, generated.prefix(), generated.hash(), clock.instant()));
        return new IssuedKey(key, generated.plainText());
    }

    /** Ключ с заранее известным текстом — только для песочницы из env ({@link SandboxBootstrap}). */
    @Transactional
    public ProviderKey registerKey(UUID providerId, String plainText) {
        ApiKeys.Parsed parsed = ApiKeys.parse(plainText)
                .orElseThrow(() -> new IllegalArgumentException("API key must look like nsb_<8 chars>_<43 chars>"));
        return keys.save(new ProviderKey(providerId, parsed.prefix(), ApiKeys.hash(parsed.secret()), clock.instant()));
    }

    /** Отзыв действует сразу: revoked_at проверяется на каждом запросе. */
    @Transactional
    public ProviderKey revokeKey(UUID providerId, UUID keyId) {
        ProviderKey key = keys.findByIdAndProviderId(keyId, providerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.KEY_NOT_FOUND, "key not found"));
        key.revoke(clock.instant());
        return keys.save(key);
    }

    /**
     * Проверка ключа из заголовка. Пусто — ключ неверный (нет такого префикса, хеш не совпал, отозван):
     * причину наружу не раскрываем, вызывающий отвечает одинаковым 401.
     */
    @Transactional(readOnly = true)
    public Optional<Authenticated> authenticate(String rawKey) {
        Optional<ApiKeys.Parsed> parsed = ApiKeys.parse(rawKey);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        return keys.findByPrefix(parsed.get().prefix())
                .filter(key -> !key.isRevoked())
                .filter(key -> ApiKeys.matches(parsed.get().secret(), key.getHash()))
                .flatMap(key -> providers.findById(key.getProviderId()).map(p -> new Authenticated(p, key.getId())));
    }

    @Transactional
    public void touchKey(UUID keyId, Instant at) {
        keys.touch(keyId, at);
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return providers.count() == 0;
    }

    private static ApiException providerNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.PROVIDER_NOT_FOUND, "provider not found");
    }

    private static ApiException nameTaken() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.PROVIDER_NAME_TAKEN, "provider with this name exists");
    }

    public record IssuedKey(ProviderKey key, String plainText) {
    }

    public record Authenticated(Provider provider, UUID keyId) {
    }
}
