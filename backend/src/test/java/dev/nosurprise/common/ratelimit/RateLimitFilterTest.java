package dev.nosurprise.common.ratelimit;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Поведение фильтра: только POST /api/calc; 429 + Retry-After + ProblemDetail code=RATE_LIMITED;
 * аноним → ключ по IP, аутентифицированный → ключ по имени (будущий userId);
 * сбой rate limiter'а не ломает /api/calc.
 * Standalone MockMvc без контекста Spring — фильтр добавляется вручную.
 */
class RateLimitFilterTest {

    private static final String IP = "198.51.100.23";
    private static final String USER = "550e8400-e29b-41d4-a716-446655440000";

    @RestController
    static class StubController {
        @PostMapping("/api/calc")
        String calc() {
            return "{\"ok\":true}";
        }
    }

    RateLimiter limiter;
    RateLimitFilter filter;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        RateLimitProperties properties = new RateLimitProperties();
        RateLimitAuthProperties authProperties = new RateLimitAuthProperties();
        filter = new RateLimitFilter(limiter, properties, authProperties, new tools.jackson.databind.ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .addFilters(filter)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("аноним: разрешено → запрос проходит, ключ по IP, лимит 30")
    void anonymousAllowed() throws Exception {
        when(limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30)).thenReturn(RateLimitDecision.allow());

        mockMvc.perform(post("/api/calc").with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                }))
                .andExpect(status().isOk());

        verify(limiter).tryAcquire(RateLimiter.SCOPE_CALC, IP, 30);
    }

    @Test
    @DisplayName("аноним: превышение → 429, Retry-After, ProblemDetail code=RATE_LIMITED")
    void anonymousLimited() throws Exception {
        when(limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30)).thenReturn(RateLimitDecision.limited(42));

        mockMvc.perform(post("/api/calc").with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("аутентифицированный: ключ по userId, лимит 120")
    void authenticatedUserKeyAndLimit() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(limiter.tryAcquire(RateLimiter.SCOPE_CALC, eq(USER), eq(120))).thenReturn(RateLimitDecision.allow());

        mockMvc.perform(post("/api/calc"))
                .andExpect(status().isOk());

        verify(limiter).tryAcquire(RateLimiter.SCOPE_CALC, USER, 120);
    }

    @Test
    @DisplayName("GET /api/calc/{calcId} не лимитируется — фильтр пропускает без проверки")
    void getRequestNotLimited() throws Exception {
        mockMvc.perform(get("/api/calc/665f0f0f1e1e1e1e1e1e1e1e"))
                .andExpect(status().isNotFound()); // standalone: нет GET-контроллера, но фильтр не срабатывал

        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("POST /api/auth/login лимитируется по IP (10/мин), даже для аутентифицированных")
    void loginLimitedByIp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        when(limiter.tryAcquire(RateLimiter.SCOPE_AUTH_LOGIN, IP, 10)).thenReturn(RateLimitDecision.limited(30));

        mockMvc.perform(post("/api/auth/login").with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        verify(limiter).tryAcquire(RateLimiter.SCOPE_AUTH_LOGIN, IP, 10);
    }

    @Test
    @DisplayName("POST /api/auth/otp/verify лимитируется по IP отдельным лимитом (10/мин)")
    void otpVerifyLimitedByIp() throws Exception {
        when(limiter.tryAcquire(RateLimiter.SCOPE_AUTH_OTP_VERIFY, IP, 10)).thenReturn(RateLimitDecision.allow());

        mockMvc.perform(post("/api/auth/otp/verify").with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                }))
                .andExpect(status().isNotFound()); // standalone: нет контроллера, но фильтр отработал

        verify(limiter).tryAcquire(RateLimiter.SCOPE_AUTH_OTP_VERIFY, IP, 10);
    }

    @Test
    @DisplayName("POST на прочие пути (не calc, не auth) не лимитируется")
    void otherPostsNotLimited() throws Exception {
        mockMvc.perform(post("/api/offers"))
                .andExpect(status().isNotFound()); // standalone: нет контроллера

        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("сбой Redis (исключение limiter'а) не ломает POST /api/calc")
    void redisFailureLetsRequestThrough() throws Exception {
        when(limiter.tryAcquire(RateLimiter.SCOPE_CALC, eq(IP), eq(30))).thenThrow(new IllegalStateException("redis down"));

        mockMvc.perform(post("/api/calc").with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("429-ответ: точный content-type application/problem+json")
    void problemJsonContentType() throws Exception {
        when(limiter.tryAcquire(RateLimiter.SCOPE_CALC, IP, 30)).thenReturn(RateLimitDecision.limited(1));

        mockMvc.perform(post("/api/calc").with(request -> {
                    request.setRemoteAddr(IP);
                    return request;
                }))
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo("application/problem+json"));
    }
}
