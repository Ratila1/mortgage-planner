package dev.nosurprise.policy.common;

/** Коды ошибок API сервиса кредитной политики (поле code в ProblemDetail). */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_REQUEST_BODY,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONCURRENT_MODIFICATION,
    RULES_INVALID,
    RULES_VERSION_NOT_FOUND,
    BLACKLIST_ENTRY_NOT_FOUND,
    BLACKLIST_KEY_REQUIRED,
    SCORING_NOT_FOUND,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
