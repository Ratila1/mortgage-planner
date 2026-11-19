package dev.nosurprise.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Authenticates service-to-service calls by the shared X-Service-Token header. */
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";

    private final byte[] expectedToken;
    private final AuthenticationEntryPoint entryPoint;

    public ServiceTokenFilter(String expectedToken, AuthenticationEntryPoint entryPoint) {
        this.expectedToken = expectedToken == null ? new byte[0] : expectedToken.getBytes(StandardCharsets.UTF_8);
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided.getBytes(StandardCharsets.UTF_8), expectedToken)
                || expectedToken.length == 0) {
            AuthenticationException ex = new InsufficientAuthenticationException("missing or invalid service token");
            entryPoint.commence(request, response, ex);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("service", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.security.MessageDigest.isEqual(digest.digest(left), digest.digest(right));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
