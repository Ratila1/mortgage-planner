package dev.nosurprise.auth.service;

/**
 * Результат логина/refresh: access-токен в тело ответа, refresh — cookie-значение
 * {userId}.{token} для httpOnly-cookie (публикует контроллер).
 */
public record LoginResult(String accessToken, long expiresIn, String refreshTokenCookie) {
}
