package dev.nosurprise.auth.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Выданный refresh-токен: opaque-токен (не JWT), cookie-значение {userId}.{token}
 * (userId нужен, потому что ключ Redis содержит userId — opaque-токен сам пользователя
 * не определяет) и момент истечения (now + refreshTtl).
 */
public record IssuedRefreshToken(UUID userId, String token, String cookieValue, Instant expiresAt) {
}
