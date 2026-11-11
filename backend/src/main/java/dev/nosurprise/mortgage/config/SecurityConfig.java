package dev.nosurprise.mortgage.config;

import dev.nosurprise.mortgage.auth.JwtUtil;
import dev.nosurprise.mortgage.auth.User;
import dev.nosurprise.mortgage.auth.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                          JwtAuthenticationConverter jwtAuthenticationConverter) {
        return http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/calculator/**").permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/me/**").authenticated()
                        .pathMatchers("/api/scenarios/**").authenticated()
                        .pathMatchers("/api/pdf/**").authenticated()
                        .pathMatchers("/actuator/**").permitAll()
                        .anyExchange().authenticated()
                )
                .addFilterAt(jwtAuthenticationFilter(jwtAuthenticationConverter), org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, e) ->
                                Mono.fromRunnable(() -> exchange.getResponse().setStatusCode(
                                        org.springframework.http.HttpStatus.UNAUTHORIZED)))
                        .accessDeniedHandler((exchange, e) ->
                                Mono.fromRunnable(() -> exchange.getResponse().setStatusCode(
                                        org.springframework.http.HttpStatus.FORBIDDEN)))
                )
                .build();
    }

    @Bean
    public AuthenticationWebFilter jwtAuthenticationFilter(JwtAuthenticationConverter converter) {
        AuthenticationWebFilter filter = new AuthenticationWebFilter(authenticationManager(converter));
        filter.setServerAuthenticationConverter(converter);
        return filter;
    }

    @Bean
    public ReactiveAuthenticationManager authenticationManager(JwtAuthenticationConverter converter) {
        return authentication -> converter.validateToken(authentication);
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(JwtUtil jwtUtil, UserRepository userRepository) {
        return new JwtAuthenticationConverter(jwtUtil, userRepository);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public ReactiveUserDetailsService userDetailsService(UserRepository userRepository) {
        return email -> userRepository.findByEmail(email)
                .map(user -> (UserDetails) user);
    }
}







