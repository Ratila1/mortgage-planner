package dev.nosurprise.common.otp;

/** Код запрошен раньше, чем через 60 секунд после предыдущего → 429. */
public class CodeCooldownException extends RuntimeException {
    public CodeCooldownException() {
        super("code was requested too recently, try again in a minute");
    }
}
