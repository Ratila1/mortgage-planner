package dev.nosurprise.common.ratelimit;

import dev.nosurprise.common.problem.ErrorCode;
import dev.nosurprise.common.problem.ProblemJson;
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
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Rate limit POST (§6.3, §7):
 * - /api/calc и /api/calc/stress — аноним по IP, пользователь по userId;
 * - /api/auth/* (login, register, login-link, login-link/consume) — по IP, всегда
 *   (защита от перебора паролей, OTP, массовых регистраций и почтовой бомбардировки).
 * Каждый путь — свой scope в Redis-ключе: лимиты разных эндпоинтов не выжигают друг друга.
 * IP приходит из request.getRemoteAddr(): при forward-headers-strategy: framework
 * Spring уже подставляет клиентский IP из X-Forwarded-For доверенного прокси (nginx/Cloudflare).
 * Principal пользователя — authentication.getName() (будущий userId из JWT-subject),
 * поэтому фильтр должен стоять ПОСЛЕ SecurityFilterChain.
 * Ответ при превышении: 429, заголовок Retry-After, ProblemDetail с code=RATE_LIMITED (§9).
 * Сбой Redis: для /api/calc — fail-open (warning и пропуск, как в calc.cache);
 * для /api/auth/* — fail-closed (503): без лимитов открыта брутфорс-защита паролей и OTP.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    static final String LIMIT_PATH = "/api/calc";
    static final java.util.Set<String> LIMIT_PATHS = java.util.Set.of("/api/calc", "/api/calc/stress");
    static final String LOGIN_PATH = "/api/auth/login";
    static final String REGISTER_PATH = "/api/auth/register";
    static final String LINK_REQUEST_PATH = "/api/auth/login-link";
    static final String LINK_CONSUME_PATH = "/api/auth/login-link/consume";
    static final String REFRESH_PATH = "/api/auth/refresh";
    static final String LOGOUT_PATH = "/api/auth/logout";
    static final java.util.Set<String> AUTH_LIMIT_PATHS =
            java.util.Set.of(LOGIN_PATH, REGISTER_PATH, LINK_REQUEST_PATH, LINK_CONSUME_PATH, REFRESH_PATH, LOGOUT_PATH);

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final RateLimitAuthProperties authProperties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter limiter, RateLimitProperties properties,
                           RateLimitAuthProperties authProperties, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.authProperties = authProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !LIMIT_PATHS.contains(uri) && !AUTH_LIMIT_PATHS.contains(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (AUTH_LIMIT_PATHS.contains(uri)) {
            filterAuthPath(request, response, chain, uri);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        String principal = authenticated ? authentication.getName() : request.getRemoteAddr();
        int limit = authenticated
                ? properties.getAuthenticatedPerMinute()
                : properties.getAnonymousPerMinute();
        continueOrLimit(request, response, chain, RateLimiter.SCOPE_CALC, principal, limit, true);
    }

    private void filterAuthPath(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                String uri) throws ServletException, IOException {
        String principal = request.getRemoteAddr();
        String scope;
        int limit;
        switch (uri) {
            case LINK_CONSUME_PATH -> {
                scope = RateLimiter.SCOPE_AUTH_LINK_CONSUME;
                limit = authProperties.getLinkConsumePerMinute();
            }
            case LINK_REQUEST_PATH -> {
                scope = RateLimiter.SCOPE_AUTH_LINK_REQUEST;
                limit = authProperties.getLinkRequestPerMinute();
            }
            case REGISTER_PATH -> {
                scope = RateLimiter.SCOPE_AUTH_REGISTER;
                limit = authProperties.getRegisterPerMinute();
            }
            case REFRESH_PATH -> {
                scope = RateLimiter.SCOPE_AUTH_REFRESH;
                limit = authProperties.getRefreshPerMinute();
            }
            case LOGOUT_PATH -> {
                scope = RateLimiter.SCOPE_AUTH_LOGOUT;
                limit = authProperties.getLogoutPerMinute();
            }
            case LOGIN_PATH -> {
                scope = RateLimiter.SCOPE_AUTH_LOGIN;
                limit = authProperties.getLoginPerMinute();
            }
            default -> throw new IllegalStateException("unexpected auth path: " + uri);
        }
        continueOrLimit(request, response, chain, scope, principal, limit, false);
    }

    private void continueOrLimit(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                                 String scope, String principal, int limit, boolean failOpen)
            throws ServletException, IOException {
        RateLimitDecision decision;
        try {
            decision = limiter.tryAcquire(scope, sanitize(principal), limit);
        } catch (RuntimeException ex) {
            if (failOpen) {
                log.warn("rate limiter unavailable, allowing request: {}", sanitize(principal), ex);
                chain.doFilter(request, response);
                return;
            }
            log.warn("rate limiter unavailable, rejecting auth request: {}", sanitize(principal), ex);
            writeServiceUnavailable(response);
            return;
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response, decision);
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        ProblemJson.write(objectMapper, response, HttpStatus.TOO_MANY_REQUESTS,
                "rate limit exceeded: too many requests, try again later",
                Map.of("code", ErrorCode.RATE_LIMITED.name()));
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        ProblemJson.write(objectMapper, response, HttpStatus.SERVICE_UNAVAILABLE,
                "rate limiter is unavailable, try again later",
                Map.of("code", ErrorCode.RATE_LIMIT_UNAVAILABLE.name()));
    }

    private static String sanitize(String principal) {
        return principal == null ? "null" : principal.replaceAll("[\\r\\n\\t]", "_");
    }
}