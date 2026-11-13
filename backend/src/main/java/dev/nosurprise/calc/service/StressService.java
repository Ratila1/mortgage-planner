package dev.nosurprise.calc.service;

import dev.nosurprise.calc.web.CalcRequestMapper;
import dev.nosurprise.calc.web.dto.CalcRequest;
import dev.nosurprise.calc.web.dto.StressRequest;
import dev.nosurprise.calc.web.dto.StressResponse;
import dev.nosurprise.calculator.InvalidCalculationException;
import dev.nosurprise.calculator.LoanParams;
import dev.nosurprise.calculator.MortgageCalculator;
import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class StressService {

    public List<StressResponse> stress(StressRequest request) {
        Metrics.counter("nosurprise.stress.requests").increment();
        if (request.delta() != null && request.deltas() != null) {
            throw new InvalidCalculationException("delta and deltas cannot both be provided");
        }
        List<BigDecimal> deltas;
        if (request.delta() != null) {
            deltas = List.of(request.delta());
        } else if (request.deltas() != null && !request.deltas().isEmpty()) {
            deltas = request.deltas();
        } else {
            throw new InvalidCalculationException("delta or deltas is required");
        }
        for (BigDecimal delta : deltas) {
            if (delta == null) {
                throw new InvalidCalculationException("delta must not be null");
            }
            if (delta.compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidCalculationException("delta must be >= 0");
            }
        }

        LoanParams base = CalcRequestMapper.toParams(new CalcRequest(
                request.price(), request.downPayment(), request.termMonths(), request.annualRate(),
                request.type(), request.earlyRepayments()));

        List<StressResponse> result = new ArrayList<>();
        for (BigDecimal delta : deltas) {
            BigDecimal stressedRate = base.annualRate().add(delta);
            if (stressedRate.compareTo(BigDecimal.ZERO) < 0 || stressedRate.compareTo(new BigDecimal("100")) > 0) {
                throw new InvalidCalculationException("stressed annualRate must be in 0..100");
            }
            LoanParams stressed = new LoanParams(base.price(), base.downPayment(), base.termMonths(),
                    stressedRate, base.type(), base.earlyRepayments());
            var calculation = new MortgageCalculator().calculate(stressed);
            result.add(new StressResponse(delta,
                    calculation.summary().monthlyPayment().toPlainString(),
                    calculation.summary().overpayment().toPlainString()));
        }
        return result;
    }
}