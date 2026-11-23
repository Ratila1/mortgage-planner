package dev.nosurprise.sms.outbox;

public enum OutboxStatus {
    NEW,
    RETRY,
    PROCESSED,
    DEAD
}
