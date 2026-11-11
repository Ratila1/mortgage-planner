package dev.nosurprise.common.ratelimit;

import dev.nosurprise.common.problem.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate limit POST /api/calc (§6.3, §7): аноним — по IP, пользователь — по userId.
 * IP приходит из request.getRemoteAddr(): при forward-headers-strategy: framework
 * Spring уже подставляет клиентский IP из X-Forwarded-For доверенного прокси (nginx/Cloudflare).
 * Principal пользователя — authentication.getName() (будущий userId из JWT-subject),
 * поэтому фильтр должен стоять ПОСЛЕ SecurityFilterChain.
 * Ответ при превышении: 429, заголовок Retry-After, ProblemDetail с code=RATE_LIMITED (§9).
 * Сбой Redis не ломает /api/calc: warning и пропуск запроса (как в calc.cache).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String LIMIT_PATH = "/api/calc";
    static final java.util.Set<String> LIMIT_PATHS = java.util.Set.of("/api/calc", "/api/calc/stress");
    static final String PROBLEM_TYPE = "about:blank";

    private final RateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    /**
     * Совместимость с прежним API (Jackson 2/3 ObjectMapper передавался снаружи).
     * JSON 429-ответа собирается вручную, сериализатор не используется — параметр игнорируется.
     * Конструктор оставлен, чтобы не ломать вызовы/тесты, ожидающие ObjectMapper-аргумент.
     */
    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties, Object objectMapper) {
        this(limiter, properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !LIMIT_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        String principal = authenticated ? authentication.getName() : request.getRemoteAddr();
        int limit = authenticated
                ? properties.getAuthenticatedPerMinute()
                : properties.getAnonymousPerMinute();

        RateLimitDecision decision;
        try {
            decision = limiter.tryAcquire(principal, limit);
        } catch (RuntimeException ex) {
            log.warn("rate limiter unavailable, allowing request: {}", principal, ex);
            chain.doFilter(request, response);
            return;
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response, decision);
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"" + PROBLEM_TYPE
                + "\",\"title\":\"" + HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase()
                + "\",\"status\":" + HttpStatus.TOO_MANY_REQUESTS.value()
                + ",\"detail\":\"rate limit exceeded: too many calculation requests, try again later\""
                + ",\"code\":\"" + ErrorCode.RATE_LIMITED.name() + "\"}");
    }
}
