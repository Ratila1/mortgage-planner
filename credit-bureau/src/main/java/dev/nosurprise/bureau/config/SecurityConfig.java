package dev.nosurprise.bureau.config;

import dev.nosurprise.bureau.common.ErrorCode;
import dev.nosurprise.bureau.common.Problems;
import dev.nosurprise.bureau.provider.ProviderService;
import dev.nosurprise.bureau.security.ApiKeyAuthenticationFilter;
import dev.nosurprise.bureau.security.AuthFailureLimiter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.time.Clock;

/**
 * Доступ (CREDIT_BUREAU.md §6.3, §7, §9): /api/v1/** — только провайдеры по X-Api-Key, /api/admin/** — только
 * админ бюро по X-Admin-Key; Swagger и health открыты. Stateless: ни сессий, ни cookie, ни CSRF —
 * клиенты бюро серверные. Пути — без context-path /bureau.
 */
@Configuration
public class SecurityConfig {

    @Bean
    ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(ProviderService providers, AuthFailureLimiter failures,
                                                          StringRedisTemplate redis, BureauProperties properties,
                                                          Problems problems, Clock clock) {
        return new ApiKeyAuthenticationFilter(providers, failures, redis, properties, problems, clock);
    }

    /** Фильтр работает только внутри цепочки Spring Security; отдельную регистрацию в контейнере выключаем. */
    @Bean
    FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyFilterRegistration(ApiKeyAuthenticationFilter filter) {
        FilterRegistrationBean<ApiKeyAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyFilter,
                                            Problems problems) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/v1/**").hasRole("PROVIDER")
                        .requestMatchers("/api/admin/**").hasRole("BUREAU_ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> problems.write(response,
                                HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_API_KEY,
                                "credentials required: header X-Api-Key (provider) or X-Admin-Key (admin)", 0))
                        .accessDeniedHandler((request, response, ex) -> problems.write(response,
                                HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "access denied", 0)));
        return http.build();
    }
}
