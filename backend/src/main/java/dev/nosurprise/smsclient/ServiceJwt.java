package dev.nosurprise.smsclient;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Service-to-service JWT для SMS-сервиса: HS256 на общем JWT_SECRET (sms-spec §7).
 * Подписывается на лету, поэтому в окружении не нужен заранее сгенерированный токен.
 */
final class ServiceJwt {

    static final String SUBJECT = "sms-client";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final SecretKey key;

    ServiceJwt(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("SMS client service JWT secret is not configured");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    String mint() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(SUBJECT)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(TTL)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(key));
        } catch (JOSEException ex) {
            throw new IllegalStateException("Service JWT signing failed", ex);
        }
        return jwt.serialize();
    }
}
