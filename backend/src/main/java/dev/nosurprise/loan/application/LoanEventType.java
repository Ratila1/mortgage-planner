package dev.nosurprise.loan.application;

import java.util.EnumSet;
import java.util.Set;

/**
 * Типы строк журнала loan_events (MICROLOANS.md §3.14): смена статуса и проколы клиента.
 * Тип хранится строкой — новые проколы добавляются без смены схемы документа в Mongo.
 */
public enum LoanEventType {
    STATUS_CHANGED,
    OFFER_NOT_SIGNED,
    SIGN_CODE_SENT,
    SIGN_CODE_FAILED,
    SIGN_CODE_BURNED,
    SIGN_BLOCKED_PHONE_NOT_VERIFIED,
    /** Клиенту ушло напоминание о просрочке (details.day — день просрочки). Не прокол: сама просрочка — статус. */
    OVERDUE_REMINDER_SENT;

    private static final Set<LoanEventType> INCIDENTS =
            EnumSet.of(OFFER_NOT_SIGNED, SIGN_CODE_FAILED, SIGN_CODE_BURNED, SIGN_BLOCKED_PHONE_NOT_VERIFIED);

    /** Прокол — событие, которое говорит о ненадёжности клиента; попадает в incidents кредитной истории. */
    public boolean isIncident() {
        return INCIDENTS.contains(this);
    }
}
