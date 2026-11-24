package dev.nosurprise.sms.telegram.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Validates Telegram's secret_token header on the webhook.
 *
 * <p>Telegram cannot present a JWT, so this shared secret is the only proof that an
 * update really came from Bot API. An empty configured secret fails closed: the
 * webhook is rejected until a secret is configured, which is safe for polling-only
 * deployments.
 */
public class TelegramWebhookSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final byte[] expected;

    public TelegramWebhookSecretFilter(String secret) {
        this.expected = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (expected.length == 0 || provided == null || !constantTimeEquals(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String provided) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(digest.digest(expected),
                    digest.digest(provided.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
