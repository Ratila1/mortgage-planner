package dev.nosurprise.auth.service;

/**
 * Access JWT невалиден (подпись, формат, истёкший срок). Пробрасывается из TokenService.validate;
 * HTTP-маппинг (401) делает resource server / GlobalExceptionHandler (§9).
 */
public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException() {
        super("access token is invalid or expired");
    }
}
