package dev.nosurprise.user;

/**
 * Способ создания учётной записи (§6.1). Хранится строкой в users.provider
 * (CHECK-констрейнт; расширение — миграция констрейнта, не ALTER TYPE).
 */
public enum Provider {
    LOCAL,
    GOOGLE,
    GITHUB
}
