package dev.nosurprise.calc.service;

import dev.nosurprise.calculator.EarlyRepayment;
import dev.nosurprise.calculator.LoanParams;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;import java.util.List;

/**
 * paramsHash (§6.3): sha256 от канонического вида параметров.
 * BigDecimal — stripTrailingZeros().toPlainString() (1.50 ≡ 1.5),
 * досрочные погашения — в отсортированном порядке (порядок в списке не влияет на хэш).
 * Чистый Java, без Spring.
 */
public final class ParamsHasher {

    private static final Comparator<EarlyRepayment> EARLY_ORDER = Comparator
            .comparing(EarlyRepayment::type)
            .thenComparing(er -> er.month() == null ? 0 : er.month())
            .thenComparing(er -> er.startMonth() == null ? 0 : er.startMonth())
            .thenComparing(EarlyRepayment::everyMonths)
            .thenComparing(er -> er.untilMonth() == null ? 0 : er.untilMonth())
            .thenComparing(EarlyRepayment::amount)
            .thenComparing(EarlyRepayment::mode);

    private ParamsHasher() {
    }

    public static String hash(LoanParams params) {
        List<String> canonical = new ArrayList<>();
        canonical.add(canonical(params.price()));
        canonical.add(canonical(params.downPayment()));
        canonical.add(Integer.toString(params.termMonths()));
        canonical.add(params.type().name());
        canonical.add(canonical(params.annualRate()));
        params.earlyRepayments().stream()
                .sorted(EARLY_ORDER)
                .map(ParamsHasher::canonical)
                .forEach(canonical::add);
        return sha256(String.join("|", canonical));
    }

    private static String canonical(EarlyRepayment er) {
        return String.join("|",
                er.type().name(),
                er.month() == null ? "" : Integer.toString(er.month()),
                er.startMonth() == null ? "" : Integer.toString(er.startMonth()),
                er.everyMonths() == null ? "" : Integer.toString(er.everyMonths()),
                er.untilMonth() == null ? "" : Integer.toString(er.untilMonth()),
                canonical(er.amount()),
                er.mode().name());
    }

    private static String canonical(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
