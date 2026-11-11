package com.bank.mortgage.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenTtlMinutes;
    private final long refreshTokenTtlDays;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.access-token-ttl-minutes:15}") long accessTokenTtlMinutes,
                   @Value("${jwt.refresh-token-ttl-days:30}") long refreshTokenTtlDays) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT secret must be configured via environment variable JWT_SECRET");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters long");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenTtlMinutes * 60);
        String roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.id())
                .claim("roles", roles)
                .claim("type", "access")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(refreshTokenTtlDays * 24 * 60 * 60);

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.id())
                .claim("type", "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            // Quick structural validation: JWT must have exactly 3 parts separated by dots
            if (token == null || token.isEmpty()) {
                return false;
            }
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            // Validate each part is valid base64url
            for (String part : parts) {
                if (part.isEmpty() || !isValidBase64Url(part)) {
                    return false;
                }
            }
            // Verify signature part has valid length for HS256 (43), HS384 (64), or HS512 (86) base64url chars
            // corresponding to 32, 48, 64 bytes
            int sigLen = parts[2].length();
            if (sigLen != 43 && sigLen != 64 && sigLen != 86) {
                return false;
            }
            // Parse and verify signature, also check required claims exist
            Jws<Claims> jws = parseToken(token);
            Claims claims = jws.getPayload();
            // Verify required claims are present
            if (claims.get("userId") == null || claims.get("type") == null) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidBase64Url(String str) {
        // base64url alphabet: A-Z, a-z, 0-9, -, _
        return str.matches("^[A-Za-z0-9_-]+$");
    }

    public Jws<Claims> parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (JwtException e) {
            throw new JwtException("Invalid token", e);
        }
    }

    public boolean isAccessToken(String token) {
        try {
            return "access".equals(parseToken(token).getPayload().get("type"));
        } catch (JwtException e) {
            return false;
        }
    }

    public boolean isRefreshToken(String token) {
        try {
            return "refresh".equals(parseToken(token).getPayload().get("type"));
        } catch (JwtException e) {
            return false;
        }
    }

    public Long getUserIdFromToken(String token) {
        return parseToken(token).getPayload().get("userId", Long.class);
    }

    public String getEmailFromToken(String token) {
        return parseToken(token).getPayload().getSubject();
    }

    public Set<Role> getRolesFromToken(String token) {
        String rolesStr = parseToken(token).getPayload().get("roles", String.class);
        if (rolesStr == null || rolesStr.isEmpty()) {
            return Set.of();
        }
        return java.util.Arrays.stream(rolesStr.split(","))
                .map(r -> r.replace("ROLE_", ""))
                .map(Role::valueOf)
                .collect(Collectors.toSet());
    }

    public Instant getExpirationFromToken(String token) {
        return parseToken(token).getPayload().getExpiration().toInstant();
    }
}