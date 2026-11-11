package dev.nosurprise.auth.service;

/**
 * Refresh-токен невалиден: отсутствует в Redis (истёк, отозван или провернут ротацией),
 * либо cookie имеет чужой формат. Одинаковая ошибка для всех причин — детали не раскрываем.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("refresh token is invalid or expired");
    }
}
