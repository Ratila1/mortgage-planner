package dev.nosurprise.loan.application;

import dev.nosurprise.customer.ProfileView;
import dev.nosurprise.customer.Sex;
import dev.nosurprise.loan.calc.LoanQuote;
import dev.nosurprise.loan.calc.LoanQuoteCalculator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Тестовые заявки и события журнала: доступ к package-private фабрикам из того же пакета. */
public final class LoanFixtures {

    private LoanFixtures() {
    }

    public static LoanApplication submitted(UUID userId, String amount, int termDays, String income, Instant now) {
        LoanQuote quote = LoanQuoteCalculator.quote(new BigDecimal(amount), termDays, new BigDecimal("1.000"),
                new BigDecimal("20.00"));
        ProfileView profile = new ProfileView(userId, "Иванов", "Иван", null, "+375291234567", true,
                new BigDecimal(income), LocalDate.of(1985, 3, 1), Sex.MALE, "BY", "Минск", now, 0L);
        return submit(userId, amount, termDays, income, now, false);
    }

    public static LoanApplication signedAtSubmit(UUID userId, String amount, int termDays, String income, Instant now) {
        return submit(userId, amount, termDays, income, now, true);
    }

    private static LoanApplication submit(UUID userId, String amount, int termDays, String income, Instant now,
                                          boolean signedAtSubmit) {
        LoanQuote quote = LoanQuoteCalculator.quote(new BigDecimal(amount), termDays, new BigDecimal("1.000"),
                new BigDecimal("20.00"));
        ProfileView profile = new ProfileView(userId, "Иванов", "Иван", null, "+375291234567", true,
                new BigDecimal(income), LocalDate.of(1985, 3, 1), Sex.MALE, "BY", "Минск", now, 0L);
        return LoanApplication.submit(userId, UUID.randomUUID(), quote, profile, UUID.randomUUID(), signedAtSubmit, now);
    }

    public static LoanEvent event(long id, UUID applicationId, LoanEventType type, LoanStatus to, UUID actor,
                                  Instant at) {
        LoanEvent event = new LoanEvent(applicationId, type, null, to, actor, null, at);
        try {
            var field = LoanEvent.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(event, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return event;
    }
}
