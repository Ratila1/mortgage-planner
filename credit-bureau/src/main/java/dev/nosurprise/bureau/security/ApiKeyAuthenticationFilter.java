package dev.nosurprise.bureau.security;

import dev.nosurprise.bureau.common.ErrorCode;
import dev.nosurprise.bureau.common.Problems;
import dev.nosurprise.bureau.config.BureauProperties;
import dev.nosurprise.bureau.provider.ProviderService;
import dev.nosurprise.bureau.provider.ProviderStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Проверка ключей до контроллеров (CREDIT_BUREAU.md §6.3).
 * <ul>
 *   <li>{@code X-Api-Key} — провайдер: ключ существует, не отозван, хеш совпал → ROLE_PROVIDER;
 *       провайдер приостановлен → 403 PROVIDER_SUSPENDED.</li>
 *   <li>{@code X-Admin-Key} — админ бюро: совпал с BUREAU_ADMIN_KEY → ROLE_BUREAU_ADMIN.</li>
 *   <li>Любой неверный ключ → 401 одинаковый для всех причин и +1 к счётчику неудач IP (§8).</li>
 * </ul>
 * Заголовка нет — фильтр ничего не делает: публичные пути (Swagger, health) открыты, остальные закроет
 * правило authorizeHttpRequests с ответом 401 из entry point.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    public static final String ROLE_PROVIDER = "ROLE_PROVIDER";
    public static final String ROLE_ADMIN = "ROLE_BUREAU_ADMIN";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    /** last_used_at обновляем не чаще раза в минуту на ключ, а не на каждый запрос. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final ProviderService providers;
    private final AuthFailureLimiter failures;
    private final StringRedisTemplate redis;
    private final BureauProperties properties;
    private final Problems problems;
    private final Clock clock;

    public ApiKeyAuthenticationFilter(ProviderService providers, AuthFailureLimiter failures, StringRedisTemplate redis,
                                      BureauProperties properties, Problems problems, Clock clock) {
        this.providers = providers;
        this.failures = failures;
        this.redis = redis;
        this.properties = properties;
        this.problems = problems;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        String adminKey = request.getHeader(ADMIN_KEY_HEADER);
        if (apiKey == null && adminKey == null) {
            chain.doFilter(request, response);
            return;
        }
        String ip = request.getRemoteAddr();
        try {
            long blocked = failures.blockedForSeconds(ip);
            if (blocked > 0) {
                problems.write(response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_AUTH_FAILURES,
                        "too many invalid keys from this address, retry later", blocked);
                return;
            }
            boolean authenticated = adminKey != null
                    ? authenticateAdmin(adminKey, response, ip)
                    : authenticateProvider(apiKey, response, ip);
            if (!authenticated) {
                return;
            }
        } catch (DataAccessException ex) {
            // Redis или база недоступны: без проверки ключа и лимитов не пускаем (fail closed, §11).
            log.error("authentication storage unavailable", ex);
            problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                    "service is temporarily unavailable, retry later", 0);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean authenticateProvider(String apiKey, HttpServletResponse response, String ip) throws IOException {
        var found = providers.authenticate(apiKey);
        if (found.isEmpty()) {
            failures.recordFailure(ip);
            problems.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_API_KEY, "invalid API key", 0);
            return false;
        }
        var provider = found.get().provider();
        if (provider.getStatus() == ProviderStatus.SUSPENDED) {
            problems.write(response, HttpStatus.FORBIDDEN, ErrorCode.PROVIDER_SUSPENDED,
                    "provider access is suspended", 0);
            return false;
        }
        touch(found.get().keyId());
        ProviderPrincipal principal = new ProviderPrincipal(provider.getId(), provider.getName(), found.get().keyId(),
                provider.isSandbox(), provider.getRequestsPerMinute(), provider.getRequestsPerDay());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(ROLE_PROVIDER))));
        return true;
    }

    private boolean authenticateAdmin(String adminKey, HttpServletResponse response, String ip) throws IOException {
        // Пустой BUREAU_ADMIN_KEY — админский API выключен: любой ключ неверный.
        boolean valid = properties.adminApiEnabled() && MessageDigest.isEqual(
                adminKey.getBytes(StandardCharsets.UTF_8), properties.adminKey().getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            failures.recordFailure(ip);
            problems.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_ADMIN_KEY, "invalid admin key", 0);
            return false;
        }
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "bureau-admin", null, List.of(new SimpleGrantedAuthority(ROLE_ADMIN))));
        return true;
    }

    private void touch(java.util.UUID keyId) {
        Boolean first = redis.opsForValue().setIfAbsent("bureau:keyused:" + keyId, "1", TOUCH_INTERVAL);
        if (Boolean.TRUE.equals(first)) {
            providers.touchKey(keyId, clock.instant());
        }
    }
}
