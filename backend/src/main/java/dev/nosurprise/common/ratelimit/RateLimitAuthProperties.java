package dev.nosurprise.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Лимиты auth-эндпоинтов (§7): защита от перебора паролей и OTP-кодов.
 * Проверка всегда по IP (даже для аутентифицированных) — на этих эндпоинтах
 * аутентификация ещё не состоялась или это попытка перебора.
 */
@ConfigurationProperties(prefix = "app.ratelimit.auth")
public class RateLimitAuthProperties {

    private int loginPerMinute = 10;
    private int otpVerifyPerMinute = 10;
    private int registerPerMinute = 5;
    private int otpRequestPerMinute = 5;
    private int refreshPerMinute = 30;
    private int logoutPerMinute = 30;
    private Duration window = Duration.ofSeconds(60);

    public int getLoginPerMinute() {
        return loginPerMinute;
    }

    public void setLoginPerMinute(int loginPerMinute) {
        this.loginPerMinute = loginPerMinute;
    }

    public int getOtpVerifyPerMinute() {
        return otpVerifyPerMinute;
    }

    public void setOtpVerifyPerMinute(int otpVerifyPerMinute) {
        this.otpVerifyPerMinute = otpVerifyPerMinute;
    }

    public int getRegisterPerMinute() {
        return registerPerMinute;
    }

    public void setRegisterPerMinute(int registerPerMinute) {
        this.registerPerMinute = registerPerMinute;
    }

    public int getOtpRequestPerMinute() {
        return otpRequestPerMinute;
    }

    public void setOtpRequestPerMinute(int otpRequestPerMinute) {
        this.otpRequestPerMinute = otpRequestPerMinute;
    }

    public int getRefreshPerMinute() {
        return refreshPerMinute;
    }

    public void setRefreshPerMinute(int refreshPerMinute) {
        this.refreshPerMinute = refreshPerMinute;
    }

    public int getLogoutPerMinute() {
        return logoutPerMinute;
    }

    public void setLogoutPerMinute(int logoutPerMinute) {
        this.logoutPerMinute = logoutPerMinute;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
