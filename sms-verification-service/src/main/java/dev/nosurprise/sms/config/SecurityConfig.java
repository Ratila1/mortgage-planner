package dev.nosurprise.sms.config;

import dev.nosurprise.sms.telegram.config.TelegramProperties;
import dev.nosurprise.sms.telegram.web.TelegramWebhookSecretFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Deny-by-default: каждый эндпоинт явно открыт своей цепочкой, всё остальное закрыто.
 * Раньше неизвестный путь не попадал ни в одну цепочку и оставался без защиты.
 */
@Configuration
@EnableConfigurationProperties(AppJwtProperties.class)
public class SecurityConfig {

    /** Subject service-JWT, который подписывает backend (ServiceJwt). */
    private static final String SERVICE_SUBJECT = "sms-client";

    /** SMS API: платные вызовы только с service-JWT (sub=sms-client), пользовательский токен не подходит. */
    @Bean
    @Order(1)
    public SecurityFilterChain smsApiChain(HttpSecurity http, AppJwtProperties props) throws Exception {
        http
            .securityMatcher("/api/v1/sms/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(serviceJwtDecoder(props))))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** Telegram 2FA status/confirm: пользовательский JWT backend'а (subject — id пользователя). */
    @Bean
    @Order(2)
    public SecurityFilterChain userVerificationChain(HttpSecurity http, AppJwtProperties props) throws Exception {
        http
            .securityMatcher("/api/verify/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder(props))))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** Telegram не умеет JWT: подлинность запроса доказывает secret_token из заголовка. */
    @Bean
    @Order(3)
    public SecurityFilterChain telegramWebhookChain(HttpSecurity http, TelegramProperties telegram) throws Exception {
        http
            .securityMatcher("/telegram/webhook")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(new TelegramWebhookSecretFilter(telegram.webhookSecretToken()),
                    UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /** Health/readiness нужны healthcheck'у контейнера; доступ к остальным метрикам режется сетью. */
    @Bean
    @Order(4)
    public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /** Всё, что не описано выше, — 404/403: новые эндпоинты не открываются «по умолчанию». */
    @Bean
    @Order(5)
    public SecurityFilterChain fallbackChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/error").permitAll()
                .anyRequest().denyAll())
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(AppJwtProperties props) {
        return NimbusJwtDecoder.withSecretKey(secretKey(props)).build();
    }

    /**
     * Отдельный декодер для сервисных вызовов: токен с чужим subject считается невалидным,
     * поэтому клиент получает 401, а не 403 (иначе наличие эндпоинта подтверждается чужому токену).
     */
    @Bean
    public JwtDecoder serviceJwtDecoder(AppJwtProperties props) {
        OAuth2TokenValidator<Jwt> subject = jwt -> SERVICE_SUBJECT.equals(jwt.getSubject())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                        "JWT subject must be " + SERVICE_SUBJECT, null));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(props)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), subject));
        return decoder;
    }

    private static SecretKey secretKey(AppJwtProperties props) {
        // Пустой секрет не подменяется дефолтом: подписать такой токен нельзя, и вызовы падают в 401.
        return new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
