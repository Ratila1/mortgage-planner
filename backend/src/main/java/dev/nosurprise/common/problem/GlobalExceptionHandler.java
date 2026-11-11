package dev.nosurprise.common.problem;

import dev.nosurprise.calculator.InvalidCalculationException;
import dev.nosurprise.calc.service.CalcNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 (ProblemDetail) с полем {@code code} (§9 INSTRUCTION.md).
 * IllegalArgumentException намеренно не мапится: непойманные исключения уходят в 500 INTERNAL_ERROR,
 * правила расчёта бросают InvalidCalculationException, вход DTO ловится валидацией.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("invalid request");
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST_BODY, "malformed request body");
    }

    @ExceptionHandler(InvalidCalculationException.class)
    ProblemDetail onInvalidCalculation(InvalidCalculationException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CALC_PARAMS, ex.getMessage());
    }

    @ExceptionHandler(CalcNotFoundException.class)
    ProblemDetail onCalcNotFound(CalcNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.CALC_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "unexpected error");
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
