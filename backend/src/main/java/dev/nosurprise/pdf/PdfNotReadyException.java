package dev.nosurprise.pdf;

public class PdfNotReadyException extends RuntimeException {
    public PdfNotReadyException(String message) {
        super(message);
    }
}