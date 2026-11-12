package dev.nosurprise.auth.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Refresh-токены (§7): случайный opaque-токен (НЕ JWT), в Redis только SHA-256
 * (RefreshTokenStore, TTL 30 дн). Ротация при каждом /refresh: старый ключ удаляется,
 * новый токен создаётся. logout удаляет текущий токен (идемпотентно — после ротации
 * logout по старому значению cookie просто не найдёт ключ и ничего не сделает).
 * Cookie-значение: {userId}.{opaqueToken} (userId нужен для формирования ключа Redis).
 */
@Service
public class RefreshTokenService {

    static final String COOKIE_SEPARATOR = ".";
    static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenStore store;
    private final JwtProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenStore store, JwtProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /** Создаёт новый refresh-токен для userId и сохраняет его метаданные в Redis. */
    public IssuedRefreshToken issue(UUID userId, String issuedIp) {
        String token = newToken();
        Instant now = clock.instant();
        store.create(userId, token, new RefreshTokenMetadata(now, issuedIp));
        return new IssuedRefreshToken(userId, token, cookieValue(userId, token),
                now.plus(properties.refreshTtl()));
    }

    /** Ротация: старый токен удаляется, новый выдаётся для того же пользователя. */
    public IssuedRefreshToken rotate(String cookieValue, String issuedIp) {
        ParsedCookie parsed = parse(cookieValue);
        store.read(parsed.userId(), parsed.token())
                .orElseThrow(InvalidRefreshTokenException::new);
        store.delete(parsed.userId(), parsed.token());
        return issue(parsed.userId(), issuedIp);
    }

    /** Отзыв текущего refresh-токена (logout). Отсутствующий/истёкший — не ошибка. */
    public void revoke(String cookieValue) {
        ParsedCookie parsed = parse(cookieValue);
        store.delete(parsed.userId(), parsed.token());
    }

    static String cookieValue(UUID userId, String token) {
        return userId + COOKIE_SEPARATOR + token;
    }

    private ParsedCookie parse(String cookieValue) {
        if (cookieValue == null) {
            throw new InvalidRefreshTokenException();
        }
        int separator = cookieValue.indexOf(COOKIE_SEPARATOR);
        if (separator <= 0 || separator == cookieValue.length() - 1) {
            throw new InvalidRefreshTokenException();
        }
        UUID userId;
        try {
            userId = UUID.fromString(cookieValue.substring(0, separator));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException();
        }
        return new ParsedCookie(userId, cookieValue.substring(separator + 1));
    }

    private record ParsedCookie(UUID userId, String token) {
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
