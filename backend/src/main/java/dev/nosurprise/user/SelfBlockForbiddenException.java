package dev.nosurprise.user;

/** Админ пытается заблокировать сам себя → 400 SELF_BLOCK_FORBIDDEN. */
public class SelfBlockForbiddenException extends RuntimeException {
    public SelfBlockForbiddenException() {
        super("admin cannot block themselves");
    }
}
