package com.bank.mortgage.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;

@Component
public class RateLimitFilter implements WebFilter {

    private final RateLimiterService rateLimiterService;
    private final int limit;
    private final long windowSeconds;

    public RateLimitFilter(RateLimiterService rateLimiterService,
                           @org.springframework.beans.factory.annotation.Value("${ratelimit.calculator.requests-per-minute:10}") int limit) {
        this.rateLimiterService = rateLimiterService;
        this.limit = limit;
        this.windowSeconds = 60; // 1 minute
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Only apply rate limiting to calculator endpoints
        if (!path.startsWith("/api/calculator/")) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(exchange);
        String key = "calc:" + clientIp;

        return rateLimiterService.tryAcquire(key, limit, windowSeconds)
                .flatMap(allowed -> {
                    if (!allowed) {
                        return rateLimiterService.getRetryAfterSeconds(key, windowSeconds)
                                .flatMap(retryAfter -> {
                                    ServerHttpResponse response = exchange.getResponse();
                                    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                                    response.getHeaders().add("Retry-After", String.valueOf(retryAfter));
                                    response.getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
                                    response.getHeaders().add("X-RateLimit-Remaining", "0");
                                    return response.setComplete();
                                });
                    }
                    return rateLimiterService.getRemainingRequests(key, limit, windowSeconds)
                            .flatMap(remaining -> {
                                exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(limit));
                                exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", String.valueOf(remaining));
                                return chain.filter(exchange);
                            });
                });
    }

    private String getClientIp(ServerWebExchange exchange) {
        String xForwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        InetAddress remoteAddress = exchange.getRequest().getRemoteAddress().getAddress();
        return remoteAddress != null ? remoteAddress.getHostAddress() : "unknown";
    }
}