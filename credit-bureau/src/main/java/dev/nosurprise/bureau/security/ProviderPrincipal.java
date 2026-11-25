package dev.nosurprise.bureau.security;

import java.util.UUID;

/** Кто прислал запрос: провайдер, ключ и его лимиты — лежит в SecurityContext после проверки ключа. */
public record ProviderPrincipal(UUID providerId, String providerName, UUID keyId, boolean sandbox,
                                int requestsPerMinute, int requestsPerDay) {
}
