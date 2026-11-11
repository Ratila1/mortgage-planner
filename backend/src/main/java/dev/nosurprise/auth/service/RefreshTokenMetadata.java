package dev.nosurprise.auth.service;

import java.time.Instant;

/**
 * Метаданные refresh-токена — значение ключа refresh:{userId}:{sha256(token)} (§6.3).
 * Сам opaque-токен нигде не хранится, только SHA-256 в имени ключа.
 */
public record RefreshTokenMetadata(Instant createdAt, String issuedIp) {
}
