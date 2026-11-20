package dev.nosurprise.loan.application.state;

import dev.nosurprise.loan.application.LoanApplication;
import dev.nosurprise.loan.application.LoanStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Единая точка записи метрик и подробных логов переходов состояний заявки (паттерн State).
 * Состояния — синглтоны, поэтому реестр метрик хранится статически (по умолчанию глобальный
 * реестр Micrometer, в Spring Boot подключённый к Actuator/Prometheus).
 */
public final class LoanStateMetrics {

    private static final Logger log = LoggerFactory.getLogger(LoanStateMetrics.class);

    private static volatile MeterRegistry registry;

    private LoanStateMetrics() {
    }

    /** Подмена реестра (удобно в тестах). */
    public static void setMeterRegistry(MeterRegistry meterRegistry) {
        registry = meterRegistry;
    }

    public static MeterRegistry meterRegistry() {
        return registry != null ? registry : Metrics.globalRegistry;
    }

    public static void recordEnter(LoanState state, LoanApplication context, UUID actorId, Instant now) {
        LoanStatus status = state.status();
        log.info("Loan state entered: applicationId={}, status={}, userId={}, amount={}, actorId={}, at={}",
                safeId(context), status, safeUserId(context), safeAmount(context), actorId, now);

        meterRegistry().counter("nosurprise.loan.state.enter", "status", status.name()).increment();
        recordAmount("nosurprise.loan.state.enter.amount", status.name(), null, safeAmount(context));
    }

    public static void recordExit(LoanState state, LoanApplication context, Instant now) {
        LoanStatus status = state.status();
        log.info("Loan state exited: applicationId={}, status={}, userId={}, amount={}, at={}",
                safeId(context), status, safeUserId(context), safeAmount(context), now);

        meterRegistry().counter("nosurprise.loan.state.exit", "status", status.name()).increment();
    }

    public static void recordTransition(LoanStatus from, LoanStatus to, LoanApplication context, UUID actorId, Instant now) {
        String fromName = from == null ? "NONE" : from.name();
        String toName = to.name();
        log.info("Loan state transition: applicationId={}, from={}, to={}, userId={}, amount={}, actorId={}, at={}",
                safeId(context), fromName, toName, safeUserId(context), safeAmount(context), actorId, now);

        meterRegistry().counter("nosurprise.loan.state.transitions", "from", fromName, "to", toName).increment();
        recordAmount("nosurprise.loan.state.transition.amount", fromName, toName, safeAmount(context));
    }

    private static void recordAmount(String name, String from, String to, BigDecimal amount) {
        if (amount == null) {
            return;
        }
        double value = amount.doubleValue();
        if (to == null) {
            meterRegistry().counter(name, "status", from).increment(value);
        } else {
            meterRegistry().counter(name, "from", from, "to", to).increment(value);
            DistributionSummary.builder(name + ".summary")
                    .tag("from", from)
                    .tag("to", to)
                    .register(meterRegistry())
                    .record(value);
        }
    }

    private static UUID safeId(LoanApplication context) {
        try {
            return context == null ? null : context.getId();
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID safeUserId(LoanApplication context) {
        try {
            return context == null ? null : context.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal safeAmount(LoanApplication context) {
        try {
            return context == null ? null : context.getAmount();
        } catch (Exception e) {
            return null;
        }
    }
}
