package dev.nosurprise.calculator;

/** Режим досрочного погашения (на каждое погашение). */
public enum EarlyRepaymentMode {
    /** Платёж остаётся, срок сокращается. */
    REDUCE_TERM,
    /** Срок остаётся, платёж пересчитывается на оставшийся срок. */
    REDUCE_PAYMENT
}
