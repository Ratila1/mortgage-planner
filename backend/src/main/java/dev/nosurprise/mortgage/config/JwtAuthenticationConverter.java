package dev.nosurprise.mortgage.config;

import dev.nosurprise.mortgage.auth.JwtUtil;
import dev.nosurprise.mortgage.auth.User;
import dev.nosurprise.mortgage.auth.UserRepository;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationConverter implements ServerAuthenticationConverter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public JwtAuthenticationConverter(JwtUtil jwtUtil, UserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Mono.empty();
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token) || !jwtUtil.isAccessToken(token)) {
            return Mono.empty();
        }

        Long userId = jwtUtil.getUserIdFromToken(token);
        String email = jwtUtil.getEmailFromToken(token);
        var roles = jwtUtil.getRolesFromToken(token);

        return userRepository.findById(userId)
                .filter(u -> u.email().equals(email))
                .map(user -> new JwtAuthenticationToken(user, token, roles));
    }

    // Validate an already-extracted token (used by authentication manager)
    public Mono<Authentication> validateToken(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtToken)) {
            return Mono.empty();
        }
        String token = jwtToken.getToken();
        if (!jwtUtil.validateToken(token) || !jwtUtil.isAccessToken(token)) {
            return Mono.empty();
        }
        Long userId = jwtUtil.getUserIdFromToken(token);
        String email = jwtUtil.getEmailFromToken(token);
        var roles = jwtUtil.getRolesFromToken(token);

        return userRepository.findById(userId)
                .filter(u -> u.email().equals(email))
                .map(user -> new JwtAuthenticationToken(user, token, roles));
    }
}







