package dev.nosurprise.auth.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Access JWT (§7): HS256, секрет из JWT_SECRET, TTL 15 мин, subject = userId (uuid).
 * Токен генерируется при логин/refresh и проверяется resource server'ом (Nimbus JwtDecoder
 * в SecurityConfig, тот же секрет). Никакого хранения access-токена (PostgreSQL/Redis) нет.
 * Валидация в этом классе — единая точка генерации и утилитарной проверки (unit-тесты).
 */
@Component
public class TokenService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Clock clock;

    public TokenService(JwtProperties properties, Clock clock) {
        this.key = hmacKey(properties.secret());
        this.accessTtl = properties.accessTtl();
        this.clock = clock;
    }

    /** Подписанный HS256 access-токен, subject = userId, exp = now + accessTtl. */
    public String generate(UUID userId) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException ex) {
            throw new IllegalStateException("JWT signing failed", ex);
        }
        return jwt.serialize();
    }

    /** Проверяет подпись и срок; возвращает userId из subject. Иначе — InvalidAccessTokenException. */
    public UUID validate(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(key))) {
                throw new InvalidAccessTokenException();
            }
            Date expiration = jwt.getJWTClaimsSet().getExpirationTime();
            if (expiration == null || expiration.toInstant().isBefore(clock.instant())) {
                throw new InvalidAccessTokenException();
            }
            return UUID.fromString(jwt.getJWTClaimsSet().getSubject());
        } catch (InvalidAccessTokenException ex) {
            throw ex;
        } catch (ParseException | JOSEException | IllegalArgumentException ex) {
            throw new InvalidAccessTokenException();
        }
    }

    /** SHA-256 hex строки — формат ключей refresh-токенов (§6.3): сам токен нигде не хранится. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static SecretKey hmacKey(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
