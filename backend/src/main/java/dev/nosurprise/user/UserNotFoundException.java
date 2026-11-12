package dev.nosurprise.user;

/**
 * Access-токен валиден по подписи, но пользователя с таким subject больше нет.
 * Одинаковый ответ 401 — детали не раскрываем.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("access token is invalid or expired");
    }
}
