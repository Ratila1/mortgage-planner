package dev.nosurprise.auth.web.dto;

/**
 * Ответ логина/refresh (§9): access JWT в теле (Bearer), refresh — только в httpOnly-cookie.
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    public static final String TOKEN_TYPE = "Bearer";
}
