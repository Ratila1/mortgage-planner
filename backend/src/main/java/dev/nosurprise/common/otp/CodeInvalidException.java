package dev.nosurprise.common.otp;

/** Неверный, истёкший или сгоревший одноразовый код → 400 OTP_INVALID. */
public class CodeInvalidException extends RuntimeException {
    public CodeInvalidException(String message) {
        super(message);
    }
}
