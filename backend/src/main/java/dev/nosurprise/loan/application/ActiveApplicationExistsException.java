package dev.nosurprise.loan.application;

/** У пользователя уже есть активная заявка или заём → 409 ACTIVE_APPLICATION_EXISTS (§3.8). */
public class ActiveApplicationExistsException extends RuntimeException {
    public ActiveApplicationExistsException() {
        super("user already has an active loan application");
    }
}
