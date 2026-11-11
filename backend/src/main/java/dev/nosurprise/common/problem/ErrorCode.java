package dev.nosurprise.common.problem;

/**
 * Стабильные коды ошибок HTTP API (§9): идут в ProblemDetail как поле {@code code}.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_REQUEST_BODY,
    INVALID_CALC_PARAMS,
    CALC_NOT_FOUND,
    INTERNAL_ERROR
}
