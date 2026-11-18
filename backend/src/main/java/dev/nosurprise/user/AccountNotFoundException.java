package dev.nosurprise.user;

/**
 * Пользователь не найден в админских операциях → 404 USER_NOT_FOUND.
 * Отдельно от UserNotFoundException: тот означает «токен ссылается на несуществующего пользователя» и даёт 401.
 */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException() {
        super("user not found");
    }
}
