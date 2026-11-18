package dev.nosurprise.user;

/** Пользователь заблокирован админом → 403 USER_BLOCKED (MICROLOANS.md §3.5). */
public class UserBlockedException extends RuntimeException {
    public UserBlockedException() {
        super("user is blocked");
    }
}
