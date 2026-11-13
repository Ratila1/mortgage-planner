package dev.nosurprise.calc.service;

/**
 * Расчёт по calcId не найден. В GlobalExceptionHandler мапится на 404 CALC_NOT_FOUND.
 */
public class CalcNotFoundException extends RuntimeException {

    public CalcNotFoundException(String message) {
        super(message);
    }
}
