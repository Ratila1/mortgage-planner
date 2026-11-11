package dev.nosurprise.common.problem;

import dev.nosurprise.auth.service.EmailNotVerifiedException;
import dev.nosurprise.auth.service.InvalidCredentialsException;
import dev.nosurprise.auth.service.InvalidRefreshTokenException;
import dev.nosurprise.auth.service.VerifyTokenInvalidException;
import dev.nosurprise.calculator.InvalidCalculationException;
import dev.nosurprise.calc.service.CalcNotFoundException;
import dev.nosurprise.offer.OfferNotFoundException;
import dev.nosurprise.scenario.InvalidOfferException;
import dev.nosurprise.scenario.InvalidRequestException;
import dev.nosurprise.pdf.PdfNotFoundException;
import dev.nosurprise.pdf.PdfNotReadyException;
import dev.nosurprise.notification.NotificationNotFoundException;
import dev.nosurprise.scenario.ScenarioNotFoundException;
import dev.nosurprise.user.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail onMissingParameter(MissingServletRequestParameterException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                ex.getParameterName() + ": parameter is required");
    }

    @ExceptionHandler(InvalidCalculationException.class)
    ProblemDetail onInvalidCalculation(InvalidCalculationException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_CALC_PARAMS, ex.getMessage());
    }

    @ExceptionHandler(CalcNotFoundException.class)
    ProblemDetail onCalcNotFound(CalcNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.CALC_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(OfferNotFoundException.class)
    ProblemDetail onOfferNotFound(OfferNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.OFFER_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ScenarioNotFoundException.class)
    ProblemDetail onScenarioNotFound(ScenarioNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.SCENARIO_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ProblemDetail onNotificationNotFound(NotificationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.NOTIFICATION_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PdfNotReadyException.class)
    ProblemDetail onPdfNotReady(PdfNotReadyException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.PDF_NOT_READY, ex.getMessage());
    }

    @ExceptionHandler(PdfNotFoundException.class)
    ProblemDetail onPdfNotFound(PdfNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.PDF_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidOfferException.class)
    ProblemDetail onInvalidOffer(InvalidOfferException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_OFFER, ex.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail onInvalidRequest(InvalidRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(VerifyTokenInvalidException.class)
    ProblemDetail onVerifyTokenInvalid(VerifyTokenInvalidException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VERIFY_TOKEN_INVALID, ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail onInvalidCredentials(InvalidCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, ex.getMessage());
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    ProblemDetail onEmailNotVerified(EmailNotVerifiedException ex) {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.EMAIL_NOT_VERIFIED, ex.getMessage());
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ProblemDetail onInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_REFRESH_TOKEN, ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail onUserNotFound(UserNotFoundException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, ex.getMessage());
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
