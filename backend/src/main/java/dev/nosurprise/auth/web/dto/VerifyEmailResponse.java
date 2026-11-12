package dev.nosurprise.auth.web.dto;

/**
 * Ответ GET /api/auth/verify-email?token=... (§9).
 */
public record VerifyEmailResponse(String message) {

    private static final String CONFIRMED = "Email confirmed.";

    public static VerifyEmailResponse confirmed() {
        return new VerifyEmailResponse(CONFIRMED);
    }
}
