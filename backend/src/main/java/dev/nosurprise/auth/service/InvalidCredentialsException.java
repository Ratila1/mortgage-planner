package dev.nosurprise.auth.service;

/**
 * Учётные данные неверны. Ошибка едина для «пользователь не найден» и «пароль неверный»,
 * чтобы не раскрывать существование email (§7).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid email or password");
    }
}
