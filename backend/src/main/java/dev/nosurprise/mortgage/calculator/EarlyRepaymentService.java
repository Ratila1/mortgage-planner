package dev.nosurprise.mortgage.calculator;

import reactor.core.publisher.Mono;

import java.util.List;

public interface EarlyRepaymentService {
    Mono<CalculationResponse> recalculateWithEarlyRepayments(CalculationRequest originalRequest,
                                                              List<EarlyRepayment> earlyRepayments);
}







