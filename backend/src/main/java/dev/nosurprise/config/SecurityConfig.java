package dev.nosurprise.config;

import dev.nosurprise.auth.service.JwtProperties;
import dev.nosurprise.auth.web.OAuth2AuthenticationSuccessHandler;
import dev.nosurprise.common.problem.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Безопасность (§7): stateless resource server (JWT, Nimbus, HS256, секрет из JWT_SECRET).
 * Публичные: /api/calc/**, /api/offers, /api/auth/**, /actuator/health/**;
 * /api/admin/** — ROLE_ADMIN; остальное (включая /actuator/prometheus) — аутентифицированные.
 * 401/403 — ProblemDetail (RFC 7807) с полем code, пишутся напрямую (детали — константы,
 * поэтому JSON собирается без ObjectMapper: Boot 4 автоконфигурирует Jackson 3, а не
 * com.fasterxml). OAuth2 login и logout-all — вне этапа, не реализуются. Никаких отключений
 * security и небезопасных fallback-конфигураций.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.metrics.username:prometheus}")
    private String metricsUsername;

    @Value("${app.metrics.password:prometheus}")
    private String metricsPassword;

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService metricsUserDetailsService(PasswordEncoder encoder) {
        if (metricsPassword == null || metricsPassword.isBlank()) {
            return new InMemoryUserDetailsManager();
        }
        UserDetails user = User.withUsername(metricsUsername)
                .password(encoder.encode(metricsPassword))
                .roles("METRICS")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    /** HS256-декодер Nimbus: тот же секрет, что и TokenService при генерации (§7). */
    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        SecretKey key = new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, Environment env,
                                    ObjectProvider<OAuth2AuthenticationSuccessHandler> successHandlerProvider) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/calc/**", "/api/offers", "/api/auth/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/oauth2/authorization/**", "/login/oauth2/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());

        // OAuth2 login включается только профилем oauth (application-oauth.yml).
        if (java.util.Arrays.asList(env.getActiveProfiles()).contains("oauth")) {
            OAuth2AuthenticationSuccessHandler handler = successHandlerProvider.getIfAvailable();
            if (handler != null) {
                http.oauth2Login(oauth2 -> oauth2
                        .successHandler(handler)
                        // Отказ пользователя или ошибка провайдера — назад на страницу входа фронта.
                        .failureHandler((request, response, ex) ->
                                response.sendRedirect(OAuth2AuthenticationSuccessHandler.LOGIN_ERROR_URL + "failed")));
            } else {
                http.oauth2Login(Customizer.withDefaults());
            }
        }

        http
            .oauth2ResourceServer(oauth2 -> oauth2
                // невалидный JWT — не «отсутствие аутентификации», отдельный failure-handler ресурсного сервера
                .authenticationEntryPoint(new ProblemEntryPoint())
                .accessDeniedHandler(new ProblemAccessDeniedHandler())
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new ProblemEntryPoint())
                .accessDeniedHandler(new ProblemAccessDeniedHandler()));
        return http.build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter rolesConverter = new JwtGrantedAuthoritiesConverter();
        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesConverter);
        return converter;
    }

    /** 401: нет/невалидный JWT — ProblemDetail code=UNAUTHORIZED. */
    static final class ProblemEntryPoint implements AuthenticationEntryPoint {

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException ex) throws IOException {
            writeProblem(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                    "authentication required: missing or invalid access token");
        }
    }

    /** 403: аутентифицирован, но нет роли (например, не ADMIN на /api/admin/**). */
    static final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException ex) throws IOException {
            writeProblem(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "insufficient permissions for this resource");
        }
    }

    private static void writeProblem(HttpServletResponse response, HttpStatus status,
                                     ErrorCode code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\""
                + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code.name() + "\"}");
    }
}
