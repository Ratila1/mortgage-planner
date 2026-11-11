package dev.nosurprise.calculator;

/** Тип досрочного погашения. */
public enum EarlyRepaymentType {
    /** Разовое: применяется в месяце {@code month}. */
    ONE_TIME,
    /** Регулярное: начиная с {@code startMonth} каждые {@code everyMonths} месяцев, до {@code untilMonth} включительно (или до конца срока). */
    REGULAR
}
