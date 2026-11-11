package dev.nosurprise.auth.service;

/**
 * Токен подтверждения почты отсутствует, истёк или указывает на несуществующего пользователя.
 * 400 VERIFY_TOKEN_INVALID; не раскрывает, существует ли email/пользователь.
 */
public class VerifyTokenInvalidException extends RuntimeException {

    public VerifyTokenInvalidException() {
        super("verification token is invalid or expired");
    }
}
