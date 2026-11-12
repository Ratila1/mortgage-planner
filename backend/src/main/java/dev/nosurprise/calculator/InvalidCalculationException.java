package dev.nosurprise.calculator;

/**
 * Нарушение правил расчёта (§5). Чистый Java, никаких зависимостей.
 * В веб-слое мапится на 400 + ProblemDetail c code = INVALID_CALC_PARAMS.
 * Наследует IllegalArgumentException, чтобы существующие проверки оставались совместимыми.
 */
public class InvalidCalculationException extends IllegalArgumentException {

    public InvalidCalculationException(String message) {
        super(message);
    }
}
