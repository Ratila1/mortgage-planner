package dev.nosurprise.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Лимиты POST /api/calc (§6.3): аноним 30 запросов/мин на IP, пользователь 120/мин.
 * Значения вынесены в application.yml (app.ratelimit.calc.*), окно фиксировано 60 с.
 */
@ConfigurationProperties(prefix = "app.ratelimit.calc")
public class RateLimitProperties {

    private int anonymousPerMinute = 30;
    private int authenticatedPerMinute = 120;
    private Duration window = Duration.ofSeconds(60);

    public int getAnonymousPerMinute() {
        return anonymousPerMinute;
    }

    public void setAnonymousPerMinute(int anonymousPerMinute) {
        this.anonymousPerMinute = anonymousPerMinute;
    }

    public int getAuthenticatedPerMinute() {
        return authenticatedPerMinute;
    }

    public void setAuthenticatedPerMinute(int authenticatedPerMinute) {
        this.authenticatedPerMinute = authenticatedPerMinute;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
