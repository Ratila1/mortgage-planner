package dev.nosurprise.bureau.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Фиктивные заёмщики песочницы (CREDIT_BUREAU.md §7.3): провайдер с sandbox=true получает ответы только отсюда
 * и не ходит ни в Postgres, ни в Mongo. Поиск — по тем же правилам, что боевой (SearchQuery.matches).
 * Данные перечислены в описании Swagger.
 */
public final class SandboxReports {

    public static final String GOOD_PAYER_PHONE = "+375290000001";
    public static final String RISKY_PHONE = "+375290000002";

    /** Нормализованные ФИО (нижний регистр), как во VIEW монолита. */
    record FakeBorrower(String key, String phone, String lastName, String firstName, String middleName,
                        StoredHistory history) {
    }

    private static final List<FakeBorrower> BORROWERS = List.of(
            new FakeBorrower("good", GOOD_PAYER_PHONE, "иванов", "иван", "иванович", new StoredHistory(List.of(
                    loan("REPAID", "300.00", "2026-01-10T09:00:00Z", "2026-01-10T12:00:00Z", "2026-01-30T10:00:00Z"),
                    loan("REPAID", "500.00", "2026-04-02T09:00:00Z", "2026-04-02T15:00:00Z", "2026-04-28T10:00:00Z"),
                    loan("ACTIVE", "800.00", "2026-09-15T09:00:00Z", "2026-09-15T11:00:00Z", null)))),
            new FakeBorrower("risky", RISKY_PHONE, "петров", "петр", "", new StoredHistory(List.of(
                    loan("ACTIVE", "1000.00", "2026-10-01T09:00:00Z", "2026-10-01T10:00:00Z", null)))),
            // Два однофамильца «Тестов Тест»: поиск только по ФИО → 409 AMBIGUOUS_SUBJECT.
            new FakeBorrower("namesake-1", "+375290000003", "тестов", "тест", "", new StoredHistory(List.of(
                    loan("REPAID", "200.00", "2026-03-01T09:00:00Z", "2026-03-01T10:00:00Z", "2026-03-20T10:00:00Z")))),
            new FakeBorrower("namesake-2", "+375290000004", "тестов", "тест", "", new StoredHistory(List.of(
                    loan("ACTIVE", "400.00", "2026-08-01T09:00:00Z", "2026-08-01T10:00:00Z", null)))));

    private SandboxReports() {
    }

    public static List<FakeBorrower> find(SearchQuery query) {
        return BORROWERS.stream()
                .filter(b -> query.matches(b.phone(), b.lastName(), b.firstName(), b.middleName()))
                .limit(2)
                .toList();
    }

    public static Optional<StoredHistory> history(FakeBorrower borrower) {
        return Optional.of(borrower.history());
    }

    private static StoredHistory.StoredLoan loan(String status, String amount, String submitted, String decided,
                                                 String closed) {
        return new StoredHistory.StoredLoan(status, new BigDecimal(amount), Instant.parse(submitted),
                Instant.parse(decided), closed == null ? null : Instant.parse(closed));
    }
}
