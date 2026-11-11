package dev.nosurprise.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Заглушка: калькулятор и health открыты, остальное закрыто.
 * Здесь позже появятся JWT-фильтр, OAuth2 login и роли USER/ADMIN.
 * PasswordEncoder — DelegatingPasswordEncoder (bcrypt для новых паролей, §7).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/calc/**", "/api/auth/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        return http.build();
    }
}
