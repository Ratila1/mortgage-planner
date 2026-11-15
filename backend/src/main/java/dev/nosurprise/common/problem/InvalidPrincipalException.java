package dev.nosurprise.common.problem;

/**
 * JWT subject не является корректным userId (битый/подделанный токен) → 401,
 * а не 404/500: запрос не аутентифицирован корректно (§7).
 */
public class InvalidPrincipalException extends RuntimeException {

    public InvalidPrincipalException() {
        super("invalid principal");
    }
}
