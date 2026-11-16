package dev.nosurprise.config;

import dev.nosurprise.common.ratelimit.RateLimitFilter;
import dev.nosurprise.common.ratelimit.RateLimitProperties;
import dev.nosurprise.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Минимальный Spring-контекст с RateLimitConfig/RateLimitFilter:
 * конфигурация стартует, получая Boot-овый Jackson-3 ObjectMapper
 * (в приложении его автоконфигурирует Boot 4, здесь — заглушка).
 * RateLimiter подменяется моком, чтобы не требовать Redis.
 */
class RateLimitConfigContextTest {

    @Configuration
    static class MockRateLimiterConfig {
        @Bean
        RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(RateLimitConfig.class, MockRateLimiterConfig.class);

    @Test
    void minimalContextStartsWithRateLimitFilterAndConfig() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimitConfig.class);
            assertThat(context).hasBean("rateLimitFilter");
            assertThat(context.getBean("rateLimitFilter"))
                    .isInstanceOf(FilterRegistrationBean.class);
        });
    }
}