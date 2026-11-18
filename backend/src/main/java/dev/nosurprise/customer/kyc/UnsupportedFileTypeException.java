package dev.nosurprise.customer.kyc;

/** Не JPEG и не PNG → 415 UNSUPPORTED_FILE_TYPE. */
public class UnsupportedFileTypeException extends RuntimeException {
    public UnsupportedFileTypeException() {
        super("only JPEG and PNG images are accepted");
    }
}
