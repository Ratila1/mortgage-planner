package dev.nosurprise.bureau.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/** Все ошибки API — ProblemDetail с полем code (CREDIT_BUREAU.md §7.4). Внутренние подробности наружу не уходят. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
        if (ex.retryAfterSeconds() > 0) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(Problems.of(ex.status(), ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail);
    }

    /** Нарушение ограничений на параметрах метода (например, длина Idempotency-Key). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail methodValidation(HandlerMethodValidationException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "request parameters are not valid");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail missingParameter(MissingServletRequestParameterException ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "missing parameter: " + ex.getParameterName());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception ex) {
        return Problems.of(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST_BODY,
                "request body or parameters are malformed (invalid JSON, wrong type or UUID)");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrent(ObjectOptimisticLockingFailureException ex) {
        return Problems.of(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "the record was changed by someone else, reload and retry");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail notFound(NoResourceFoundException ex) {
        return Problems.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail method(HttpRequestMethodNotSupportedException ex) {
        return Problems.of(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.NOT_FOUND, ex.getMessage());
    }

    /**
     * Redis, Mongo или база монолита недоступны → 503, а не ответ без проверок (fail closed, §11): бюро отдаёт
     * персональные данные, лимиты и журнал пропускать нельзя.
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ProblemDetail unavailable(DataAccessResourceFailureException ex) {
        log.error("storage unavailable", ex);
        return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                "service is temporarily unavailable, retry later");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("unexpected error", ex);
        return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "internal error");
    }
}
