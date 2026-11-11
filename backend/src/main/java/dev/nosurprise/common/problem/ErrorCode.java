package dev.nosurprise.common.problem;

/**
 * Стабильные коды ошибок HTTP API (§9): идут в ProblemDetail как поле {@code code}.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_REQUEST_BODY,
    INVALID_CALC_PARAMS,
    CALC_NOT_FOUND,
    OFFER_NOT_FOUND,
    SCENARIO_NOT_FOUND,
    PDF_NOT_READY,
    PDF_NOT_FOUND,
    INVALID_OFFER,
    INVALID_REQUEST,
    RATE_LIMITED,
    VERIFY_TOKEN_INVALID,
    INVALID_CREDENTIALS,
    EMAIL_NOT_VERIFIED,
    INVALID_REFRESH_TOKEN,
    UNAUTHORIZED,
    FORBIDDEN,
    INTERNAL_ERROR
}
