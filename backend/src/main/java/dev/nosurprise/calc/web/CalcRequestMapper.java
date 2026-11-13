package dev.nosurprise.calc.web;

import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.EarlyRepaymentRequest;
import dev.nosurprise.calculator.EarlyRepayment;
import dev.nosurprise.calculator.EarlyRepaymentType;
import dev.nosurprise.calculator.InvalidCalculationException;
import dev.nosurprise.calculator.LoanParams;

import java.util.List;

/**
 * CalcRequest → LoanParams. Кросс-поля ONE_TIME/REGULAR проверяет здесь:
 * DTO-валидация аннотациями этого не покрывает, а calculator сам валидирует только свою модель.
 */
public final class CalcRequestMapper {

    private CalcRequestMapper() {
    }

    public static LoanParams toParams(CalcRequest request) {
        List<EarlyRepayment> earlyRepayments = request.earlyRepayments() == null
                ? List.of()
                : request.earlyRepayments().stream().map(CalcRequestMapper::toEarlyRepayment).toList();
        try {
            return new LoanParams(request.price(), request.downPayment(), request.termMonths(),
                    request.annualRate(), request.type(), earlyRepayments);
        } catch (InvalidCalculationException ex) {
            // LoanParams валидирует модель; наружу она и так уходит как InvalidCalculationException.
            throw ex;
        }
    }

    private static EarlyRepayment toEarlyRepayment(EarlyRepaymentRequest request) {
        if (request.type() == EarlyRepaymentType.ONE_TIME && request.month() == null) {
            throw new InvalidCalculationException("month is required for ONE_TIME earlyRepayment");
        }
        if (request.type() == EarlyRepaymentType.REGULAR) {
            if (request.startMonth() == null) {
                throw new InvalidCalculationException("startMonth is required for REGULAR earlyRepayment");
            }
            if (request.everyMonths() == null) {
                throw new InvalidCalculationException("everyMonths is required for REGULAR earlyRepayment");
            }
        }
        return new EarlyRepayment(request.type(), request.month(), request.startMonth(),
                request.everyMonths(), request.untilMonth(), request.amount(), request.mode());
    }
}
