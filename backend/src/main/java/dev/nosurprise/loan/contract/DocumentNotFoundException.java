package dev.nosurprise.loan.contract;

/** Документа нет (или он ещё генерируется) → 404 DOCUMENT_NOT_FOUND. */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String message) {
        super(message);
    }
}
