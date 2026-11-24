package dev.nosurprise.sms.api.error;

import java.time.Instant;

/** Единый формат ошибки API. {@code path} убран: поле никогда не заполнялось и всегда было null. */
public record ApiError(Instant timestamp, int status, String error, String message) {}
