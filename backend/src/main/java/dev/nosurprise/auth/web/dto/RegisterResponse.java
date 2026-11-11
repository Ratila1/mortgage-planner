package dev.nosurprise.auth.web.dto;

/**
 * Ответ POST /api/auth/register. Один и тот же текст для нового и существующего email —
 * существование адреса не раскрывается (409 EMAIL_TAKEN сознательно не используется).
 */
public record RegisterResponse(String message) {

    public static final String GENERIC =
            "If the email can be registered, a confirmation letter has been sent.";

    public static RegisterResponse generic() {
        return new RegisterResponse(GENERIC);
    }
}
