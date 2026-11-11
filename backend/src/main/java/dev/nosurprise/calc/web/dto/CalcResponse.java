package dev.nosurprise.calc.web.dto;

import java.util.List;

/**
 * Ответ POST /api/calc и GET /api/calc/{calcId} (§9). Все деньги — строки.
 */
public record CalcResponse(
        String calcId,
        String loanAmount,
        String monthlyPayment,
        String overpayment,
        String totalPaid,
        int termMonths,
        /** null, если досрочных погашений не было. */
        SavingsResponse savings,
        List<ScheduleRowResponse> schedule
) {
}
