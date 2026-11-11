package dev.nosurprise.config;

import dev.nosurprise.common.ratelimit.RateLimitFilter;
import dev.nosurprise.common.ratelimit.RateLimitProperties;
import dev.nosurprise.common.ratelimit.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Регистрация RateLimitFilter: только POST /api/calc (логика в самом фильтре).
 * Порядок после SecurityFilterChain (-100), чтобы в SecurityContext уже был
 * Authentication будущего JWT-фильтра — по нему выбирается principal (userId) и лимит 120/мин.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    static final int ORDER_AFTER_SECURITY = -90;

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimiter limiter, RateLimitProperties properties) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(limiter, properties));
        registration.setOrder(ORDER_AFTER_SECURITY);
        return registration;
    }
}
