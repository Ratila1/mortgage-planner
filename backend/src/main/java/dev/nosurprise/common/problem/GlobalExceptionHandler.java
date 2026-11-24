package dev.nosurprise.common.problem;

import dev.nosurprise.auth.service.EmailNotVerifiedException;
import dev.nosurprise.auth.service.InvalidCredentialsException;
import dev.nosurprise.auth.service.OtpInvalidException;
import dev.nosurprise.auth.service.OtpRateLimitExceededException;
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
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * RFC 7807 (ProblemDetail) с полем {@code code} (§9 INSTRUCTION.md).
 * IllegalArgumentException намеренно не мапится: непойманные исключения уходят в 500 INTERNAL_ERROR,
 * правила расчёта бросают InvalidCalculationException, вход DTO ловится валидацией.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(OtpInvalidException.class)
    ProblemDetail onOtpInvalid(OtpInvalidException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.OTP_INVALID, ex.getMessage());
    }

    @ExceptionHandler(OtpRateLimitExceededException.class)
    ProblemDetail onOtpRateLimit(OtpRateLimitExceededException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, ex.getMessage());
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

    /** Битый/подделанный JWT subject → 401, а не 500/404 (§7). */
    @ExceptionHandler(InvalidPrincipalException.class)
    ProblemDetail onInvalidPrincipal(InvalidPrincipalException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, ex.getMessage());
    }

    /** Некорректный тип path-variable / query-параметра (например, id не UUID) → 400, не 500. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                ex.getName() + ": invalid value");
    }

    // --- микрозаймы (MICROLOANS.md §4.6) ---

    @ExceptionHandler(dev.nosurprise.user.AccountNotFoundException.class)
    ProblemDetail onAccountNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.USER_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.user.UserBlockedException.class)
    ProblemDetail onUserBlocked(RuntimeException ex) {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.USER_BLOCKED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.user.SelfBlockForbiddenException.class)
    ProblemDetail onSelfBlock(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.SELF_BLOCK_FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.customer.ProfileIncompleteException.class)
    ProblemDetail onProfileIncomplete(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.PROFILE_INCOMPLETE, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.customer.PhoneNotVerifiedException.class)
    ProblemDetail onPhoneNotVerified(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.PHONE_NOT_VERIFIED, ex.getMessage());
    }

    /** Several profiles share the phone; binding must not silently pick one of them. */
    @ExceptionHandler(dev.nosurprise.customer.AmbiguousPhoneException.class)
    ProblemDetail onAmbiguousPhone(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.AMBIGUOUS_PHONE, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.common.otp.CodeInvalidException.class)
    ProblemDetail onCodeInvalid(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.OTP_INVALID, ex.getMessage());
    }

    /** SMS-сервис отказал по своему лимиту (например, номер уже получал SMS в эту минуту). */
    @ExceptionHandler(dev.nosurprise.smsclient.SmsClientRateLimitedException.class)
    ProblemDetail onSmsClientRateLimited(RuntimeException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                "SMS was requested too recently, try again in a minute");
    }

    /** SMS отправить не удалось (sms-сервис недоступен или ответил ошибкой): код не выдан, можно повторить. */
    @ExceptionHandler(dev.nosurprise.smsclient.SmsClientException.class)
    ProblemDetail onSmsDelivery(RuntimeException ex) {
        return problem(HttpStatus.BAD_GATEWAY, ErrorCode.SMS_DELIVERY_FAILED, "SMS could not be sent, try again later");
    }

    @ExceptionHandler(dev.nosurprise.common.otp.CodeCooldownException.class)
    ProblemDetail onCodeCooldown(RuntimeException ex) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.customer.kyc.KycRequiredException.class)
    ProblemDetail onKycRequired(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.KYC_REQUIRED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.customer.kyc.KycNotFoundException.class)
    ProblemDetail onKycNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.KYC_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.customer.kyc.FileTooLargeException.class)
    ProblemDetail onFileTooLarge(RuntimeException ex) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.FILE_TOO_LARGE, ex.getMessage());
    }

    /** Лимит spring.servlet.multipart срабатывает до контроллера — тот же код ошибки, что и проверка в сервисе. */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ProblemDetail onMaxUploadSize(Exception ex) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, ErrorCode.FILE_TOO_LARGE, "file is too large");
    }

    @ExceptionHandler(dev.nosurprise.customer.kyc.UnsupportedFileTypeException.class)
    ProblemDetail onUnsupportedFile(RuntimeException ex) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_FILE_TYPE, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ProblemDetail onMissingPart(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "file: part is required");
    }

    @ExceptionHandler(dev.nosurprise.loan.type.LoanTypeNotFoundException.class)
    ProblemDetail onLoanTypeNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.LOAN_TYPE_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.type.LoanTypeInactiveException.class)
    ProblemDetail onLoanTypeInactive(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.LOAN_TYPE_INACTIVE, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.type.InvalidLoanTypeException.class)
    ProblemDetail onInvalidLoanType(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.calc.LoanParamsOutOfRangeException.class)
    ProblemDetail onLoanParams(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.LOAN_PARAMS_OUT_OF_RANGE, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.application.LoanApplicationNotFoundException.class)
    ProblemDetail onLoanApplicationNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.LOAN_APPLICATION_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.application.ActiveApplicationExistsException.class)
    ProblemDetail onActiveApplicationExists(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.ACTIVE_APPLICATION_EXISTS, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.application.InvalidStatusTransitionException.class)
    ProblemDetail onInvalidTransition(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.INVALID_STATUS_TRANSITION, ex.getMessage());
    }

    /** Явная проверка version и @Version Hibernate дают один и тот же ответ. */
    @ExceptionHandler({dev.nosurprise.loan.ConcurrentModificationException.class,
            org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    ProblemDetail onConcurrentModification(Exception ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "record was modified by someone else, reload and try again");
    }

    @ExceptionHandler(dev.nosurprise.loan.application.OfferExpiredException.class)
    ProblemDetail onOfferExpired(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.OFFER_EXPIRED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.application.SelfDecisionForbiddenException.class)
    ProblemDetail onSelfDecision(RuntimeException ex) {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.SELF_DECISION_FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.application.ScoringRequiredException.class)
    ProblemDetail onScoringRequired(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.SCORING_REQUIRED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.application.InvalidPaidDateException.class)
    ProblemDetail onInvalidPaidDate(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(dev.nosurprise.loan.contract.DocumentNotFoundException.class)
    ProblemDetail onDocumentNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.DOCUMENT_NOT_FOUND, ex.getMessage());
    }

    /** Неверный HTTP-метод (например, GET на POST-only /api/auth/refresh) — это 405, а не 500. */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ProblemDetail onMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                ex.getMessage());
    }

    /** The client closed the connection while the response was being written; not a server error. */
    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    void onClientAbort(Exception ex) {
        log.debug("client aborted the response", ex);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        // Стек нужен, чтобы 500-е было возможно диагностировать по логам (§8).
        log.error("unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "unexpected error");
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code.name());
        return problem;
    }
}
