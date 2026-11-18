package dev.nosurprise.customer.kyc;

/** Содержимое фото паспорта для админа. */
public record KycContent(byte[] bytes, String contentType) {
}
