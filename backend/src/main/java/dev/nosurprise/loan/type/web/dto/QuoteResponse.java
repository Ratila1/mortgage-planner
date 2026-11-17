package dev.nosurprise.loan.type.web.dto;

import dev.nosurprise.loan.calc.LoanQuote;

/** Результат калькулятора: проценты, модификатор и сумма к возврату (BYN, строками). */
public record QuoteResponse(String amount, int termDays, String dailyRate, String interest, String modifier,
                            String total) {

    public static QuoteResponse from(LoanQuote quote) {
        return new QuoteResponse(quote.amount().toPlainString(), quote.termDays(),
                quote.dailyRate().toPlainString(), quote.interest().toPlainString(),
                quote.modifier().toPlainString(), quote.total().toPlainString());
    }
}
