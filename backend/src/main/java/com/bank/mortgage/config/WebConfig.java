package com.bank.mortgage.config;

import com.bank.mortgage.ratelimit.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;

@Configuration
public class WebConfig {

    @Bean
    public WebFilter rateLimitFilter(RateLimitFilter rateLimitFilter) {
        return rateLimitFilter;
    }
}