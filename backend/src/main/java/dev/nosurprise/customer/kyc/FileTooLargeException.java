package dev.nosurprise.customer.kyc;

/** Файл больше лимита → 413 FILE_TOO_LARGE. */
public class FileTooLargeException extends RuntimeException {
    public FileTooLargeException(long maxBytes) {
        super("file is larger than " + maxBytes + " bytes");
    }
}
