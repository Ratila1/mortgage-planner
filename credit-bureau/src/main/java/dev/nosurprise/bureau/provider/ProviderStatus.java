package dev.nosurprise.bureau.provider;

/** ACTIVE — может запрашивать историю; SUSPENDED — доступ приостановлен (договор закончился, не платит). */
public enum ProviderStatus {
    ACTIVE,
    SUSPENDED
}
