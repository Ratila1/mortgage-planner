package dev.nosurprise.notificationservice.service;

import dev.nosurprise.notificationservice.domain.VerificationRequest;
import dev.nosurprise.notificationservice.domain.VerificationRequestRepository;
import dev.nosurprise.notificationservice.dto.ConfirmCodeRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class VerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final StringRedisTemplate redis;
    private final VerificationRequestRepository repository;
    private final TelegramBotService telegram;
    private final RestClient mainBackend;
    private final String serviceToken;

    public VerificationService(StringRedisTemplate redis, VerificationRequestRepository repository,
                              TelegramBotService telegram,
                              @org.springframework.beans.factory.annotation.Value("${app.main-backend.base-url}") String baseUrl,
                              @org.springframework.beans.factory.annotation.Value("${app.service.token}") String serviceToken) {
        this.redis = redis;
        this.repository = repository;
        this.telegram = telegram;
        this.mainBackend = RestClient.builder().baseUrl(baseUrl).build();
        this.serviceToken = serviceToken;
    }

    /** Вызывается consumer'ом verification.requested. */
    @Transactional
    public void handleRequested(UUID verificationId, UUID userId, String operationType, String operationRef, int ttlMinutes) {
        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofMinutes(ttlMinutes));
        repository.save(new VerificationRequest(verificationId, userId, operationType, operationRef, "PENDING", now, expires));
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForHash().put("tg:otp:" + verificationId, "hash", sha256(code));
        redis.opsForHash().put("tg:otp:" + verificationId, "userId", userId.toString());
        redis.opsForHash().put("tg:otp:" + verificationId, "operationRef", operationRef);
        redis.opsForHash().put("tg:otp:" + verificationId, "attempts", "0");
        redis.expire("tg:otp:" + verificationId, Duration.ofMinutes(ttlMinutes));
        // отправка в Telegram — по telegramId из binding (lookup опущен в скелете)
        repository.findById(verificationId).ifPresent(v -> v.setStatus("SENT"));
    }

    public boolean confirm(UUID verificationId, UUID userId, ConfirmCodeRequest req) {
        String key = "tg:otp:" + verificationId;
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        if (stored.isEmpty()) {
            // возможно уже использован
            if (Boolean.TRUE.equals(redis.hasKey("tg:otp:used:" + verificationId))) return true;
            return false;
        }
        String expected = String.valueOf(stored.get("hash"));
        if (!MessageDigest.isEqual(expected.getBytes(), sha256(req.code()).getBytes())) {
            Long attempts = redis.opsForHash().increment(key, "attempts", 1);
            if (attempts != null && attempts >= 3) redis.delete(key);
            return false;
        }
        redis.delete(key);
        redis.opsForValue().set("tg:otp:used:" + verificationId, "1", Duration.ofHours(24));
        // синхронный вызов основного бэкенда
        try {
            mainBackend.post().uri("/api/internal/verifications/" + verificationId + "/complete")
                    .header("X-Service-Token", serviceToken)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            // лог + retry через очередь; здесь упрощено
        }
        return true;
    }

    public String status(UUID verificationId) {
        if (Boolean.TRUE.equals(redis.hasKey("tg:otp:used:" + verificationId))) return "APPROVED";
        if (redis.hasKey("tg:otp:" + verificationId)) return "SENT";
        return "PENDING";
    }

    private static String sha256(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes()));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}