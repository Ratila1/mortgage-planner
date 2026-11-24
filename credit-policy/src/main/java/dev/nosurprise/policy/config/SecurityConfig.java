package dev.nosurprise.policy.config;

import dev.nosurprise.policy.common.ErrorCode;
import dev.nosurprise.policy.common.Problems;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Доступ (CREDIT_POLICY.md §7): админский API — по access-токену монолита (HS256, общий JWT_SECRET,
 * роли в claim roles), нужна роль ADMIN. Сервис токены не выпускает и не хранит пользователей.
 * Swagger и health открыты. Пути — без context-path /credit-policy.
 */
@Configuration
@EnableConfigurationProperties(PolicyProperties.class)
public class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(PolicyProperties properties) {
        SecretKey key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Problems problems,
                                            JwtAuthenticationConverter converter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/actuator/health/**", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((request, response, ex) -> problems.write(response,
                                HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                                "access token required: Authorization: Bearer <token of the main app>", 0)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> problems.write(response,
                                HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                                "access token required: Authorization: Bearer <token of the main app>", 0))
                        .accessDeniedHandler((request, response, ex) -> problems.write(response,
                                HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "admin role required", 0)));
        return http.build();
    }
}
