package dev.nosurprise.bureau.common;

import org.springframework.http.HttpStatus;

/**
 * Ошибка с HTTP-статусом и кодом — превращается в ProblemDetail в {@link GlobalExceptionHandler}.
 * retryAfterSeconds > 0 — ответ получит заголовок Retry-After (лимиты, §8).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;
    private final long retryAfterSeconds;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, 0);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
