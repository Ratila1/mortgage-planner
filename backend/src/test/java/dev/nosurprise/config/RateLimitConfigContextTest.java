package dev.nosurprise.config;

import dev.nosurprise.common.ratelimit.RateLimitFilter;
import dev.nosurprise.common.ratelimit.RateLimitProperties;
import dev.nosurprise.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Минимальный Spring-контекст с RateLimitConfig/RateLimitFilter:
 * проверяет, что конфигурация стартует без Jackson-2 ObjectMapper-бина
 * (Boot 4 автоконфигурирует Jackson 3). RateLimiter подменяется моком,
 * чтобы не требовать Redis.
 */
class RateLimitConfigContextTest {

    @Configuration
    static class MockRateLimiterConfig {
        @Bean
        RateLimiter rateLimiter() {
            return mock(RateLimiter.class);
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