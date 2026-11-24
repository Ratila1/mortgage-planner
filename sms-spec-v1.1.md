# SMS Service — Спецификация v1.1 (Revised)

**Версия:** 1.1  
**Стек:** Java 25, Spring Boot 4 (Spring Framework 7, Spring Security 6.4.x, Hibernate 7), PostgreSQL, Redis  
**Назначение:** Отдельный REST-сервис отправки OTP-кодов для заявок на микрозаймы и кредитование  
**Взаимодействие:** Только REST (ментор: "API всегда рестом выставляется")  
**База данных:** Отдельная схема `nosurprise_sms` в общем PostgreSQL  
**Асинхронность:** Cron-джоб с SKIP LOCKED (по спеку v1.0, без RabbitMQ consumer)

---

## 1. Область применения

Отдельный REST-сервис, реализующий:

- Приём запроса на отправку SMS с OTP-кодом (`POST /api/v1/sms`);
- Генерацию 6-значного кода и текста сообщения на RU/EN;
- Асинхронную отправку через симулированного провайдера (70% успеха на попытку) в профиле `sim`, реального (Vonage/Twilio) в профиле `prod`;
- Ретраи с backoff и переход в окончательный статус;
- Верификацию OTP (`POST /api/v1/sms/{id}/verify` — только service-to-service);
- Аудит попыток отправки и верификации;
- Очистку данных по retention-политике (7 дней для `sms_request`, 30 дней для `sms_attempt`).

Сервис **не** интегрирован с реальным SMS-провайдером в профиле `sim`. Провайдер реализован через интерфейс `SmsProvider` с двумя реализациями по профилям.

---

## 2. Функциональные требования

### 2.1 Создание SMS

- `POST /api/v1/sms` принимает `phone` и заголовок `Idempotency-Key` (обязателен, UUID).
- Сервис нормализует номер к E.164 (libphonenumber). Невалидный номер → `422`.
- Язык определяется по префиксу номера:
  - Префикс из списка СНГ (`+7`, `+375`, `+373`, `+374`, `+992`, `+993`, `+996`, `+998`, `+994`, `+380`, `+995`) → `RU`;
  - Иначе → `EN`.
- Генерируется OTP: 6 цифр, `SecureRandom`, с ведущими нулями (`000000`–`999999`).
- Текст формируется по шаблону:
  - RU: `Код подтверждения: {code}`, лимит 60 символов;
  - EN: `Verification code: {code}`, лимит 140 символов.
- Длина считается по Unicode code points. Превышение лимита — инвариант шаблона; нарушение → `500` + алерт, не `422`.
- Запись создаётся со статусом `IN_PROGRESS`, `attempts=0`, `next_attempt_at=now()`.
- **OTP хэш:** SHA-256 (hex) от кода; сохраняется в PostgreSQL (`otp_hash`) и Redis (`sms:otp:{id}` → `{hash, phone, attempts, ttl:5m}`).
- `message` хранится открытым текстом; `otp_expires_at = created_at + 5 мин`.
- **Redis lock per phone:** При генерации OTP устанавливается `SET NX sms:lock:phone:{e164} 1 EX 10`. Если занято — `429 TOO_MANY_REQUESTS`.
- Идемпотентность: повтор с тем же `Idempotency-Key` и тем же номером → `200` + тот же объект. Повтор с тем же ключом и другим номером → `409`.

### 2.2 Получение статуса

- `GET /api/v1/sms/{id}` возвращает `id`, `status`, `attempts`, `createdAt`, `sentAt`, `expiresAt`.
- Телефон и текст SMS в ответе **не** отдаются.

### 2.3 Верификация OTP

- `POST /api/v1/sms/{id}/verify` принимает `code`.
- **Доступ:** только service-to-service (JWT HS256, общий `JWT_SECRET` с основным бэкендом). Публичный доступ запрещён.
- Возможные результаты:
  - `200 { verified: true }` — код верный, не истёк, лимит попыток не исчерпан;
  - `200 { verified: true }` — идемпотентный повтор после успеха;
  - `200 { verified: false, reason: "INVALID_CODE" }`;
  - `200 { verified: false, reason: "EXPIRED" }` — при истёкшем `otp_expires_at`;
  - `200 { verified: false, reason: "TOO_MANY_ATTEMPTS" }` — при `verify_attempts >= 5`;
  - `409` — статус SMS `IN_PROGRESS` или `NOT_SEND` (верифицировать нечего);
  - `404` — запись не найдена.
- Лимит попыток верификации — 5. Инкремент атомарный (`UPDATE ... WHERE verify_attempts < max`).
- При успехе проставляется `verified_at` и в Redis ставится `sms:otp:used:{id}` (TTL 24h) для идемпотентности.
- Проверка кода — constant-time compare SHA-256: `MessageDigest.isEqual(sha256(code), stored_hash)`.

### 2.4 Отправка (dispatch) — Cron Job

- Cron-джоба запускается `fixedDelay = 5s`, батч до 100 записей.
- Захват записей: `UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED)` с лизом `locked_until = now() + 30s`.
- Провайдер вызывается **вне** транзакции БД.
- Результат:
  - `SUCCESS` — `status = SUCCESS`, `sent_at = now()`;
  - `FAIL` и `attempts < max_attempts` — `status = IN_PROGRESS`, `next_attempt_at = now() + backoff`;
  - `FAIL` и `attempts >= max_attempts` — `status = NOT_SEND`.
- Backoff: 15 с (после 1-й), 30 с (после 2-й).
- Каждая попытка пишется в `sms_attempt` (result, error, provider_message_id, latency_ms).
- Код OTP при ретраях **не** перегенерируется — пользователь получит тот же код при успешной доставке.
- **Метрика задержки:** `sms_dispatch_lag_seconds = now() - min(next_attempt_at)` для `IN_PROGRESS`. Алерт если > 60с.

### 2.5 Retention

- Часовая джоба удаляет `sms_request` с `created_at < now() - interval '7 days'`.
- `sms_attempt` удаляется отдельной джобой: `started_at < now() - interval '30 days'` (аудит провайдера).
- Каскадное удаление **не** используется — разные сроки хранения.

---

## 3. Нефункциональные требования

| Параметр | Значение |
|---|---|
| Языки | RU, EN |
| OTP | 6 цифр, TTL 5 мин, max 5 попыток верификации |
| max_attempts отправки | 3 |
| Итоговая вероятность SUCCESS | ~97.3% (1 − 0.3³) в профиле `sim` |
| Backoff | 15 с, 30 с |
| Cron dispatch | каждые 5 с (fixedDelay) |
| Cron retention (sms_request) | каждый час |
| Cron retention (sms_attempt) | каждый час |
| Batch size | 100 |
| Lock lease | 30 с |
| Retention sms_request | 7 дней |
| Retention sms_attempt | 30 дней |
| API latency (POST) | < 200 мс |
| Первая попытка отправки | ≤ 5 с после POST |
| Финал (SUCCESS/NOT_SEND) | ≤ 60 с |
| Доступность | 99.5% |

---

## 4. Конфигурация

### 4.1 `application.yml`

```yaml
spring:
  application:
    name: sms-service
  threads:
    virtual:
      enabled: true
  task:
    scheduling:
      pool:
        size: 2
      thread-name-prefix: sched-
  datasource:
    url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/nosurprise_sms
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  ssl:
    bundle:
      pem:
        sms-verify:
          keystore:
            certificate: ${VERIFY_CERT}
            private-key: ${VERIFY_KEY}
          truststore:
            certificate: ${VERIFY_CA}

sms:
  cis-prefixes: ["+7","+375","+373","+374","+992","+993","+996","+998","+994","+380","+995"]
  max-attempts: 3
  first-backoff: 15s
  second-backoff: 30s
  otp-ttl: 5m
  max-verify-attempts: 5
  simulated-success-rate: 0.7
  dispatch-batch-size: 100
  lock-lease: 30s
  retention-request: 7d
  rate-limit:
    phone-per-minute: 3

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: sms-service
```

### 4.2 `SmsProperties`

```java
@ConfigurationProperties(prefix = "sms")
public record SmsProperties(
    List<String> cisPrefixes,
    int maxAttempts,
    Duration firstBackoff,
    Duration secondBackoff,
    Duration otpTtl,
    int maxVerifyAttempts,
    double simulatedSuccessRate,
    int dispatchBatchSize,
    Duration lockLease,
    Duration retentionRequest,
    Duration retentionAttempt,
    RateLimit rateLimit
) {
    public record RateLimit(int phonePerMinute, int ipPerMinute) {}
}
```

### 4.3 `SchedulerConfig`

```java
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SmsProperties.class)
public class SchedulerConfig {

    @Bean("providerRandom")
    public Random providerRandom() {
        return new Random();
    }

    @Bean
    public TransactionTemplate txTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }
}
```

> В тестах `providerRandom` переопределяется на `new Random(seed)`.

---

## 5. Схема БД (Flyway `V1__sms_schema.sql`)

```sql
CREATE TABLE sms_request (
  id                  uuid PRIMARY KEY,
  idempotency_key     text NOT NULL UNIQUE,
  phone               varchar(16) NOT NULL,
  language            varchar(2)  NOT NULL,
  message             text        NOT NULL,
  status              varchar(16) NOT NULL CHECK (status IN ('IN_PROGRESS','SUCCESS','NOT_SEND')),
  attempts            int NOT NULL DEFAULT 0 CHECK (attempts <= max_attempts),
  max_attempts        int NOT NULL DEFAULT 3,
  next_attempt_at     timestamptz,
  provider_message_id text,
  last_error          text,
  locked_until        timestamptz,
  locked_by           text,
  sent_at             timestamptz,
  otp_hash            text NOT NULL,          -- SHA-256 hex
  otp_expires_at      timestamptz NOT NULL,
  verify_attempts     int NOT NULL DEFAULT 0 CHECK (verify_attempts <= max_verify_attempts),
  max_verify_attempts int NOT NULL DEFAULT 5,
  verified_at         timestamptz,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_sms_request_pending
  ON sms_request (status, next_attempt_at)
  WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_sms_request_phone ON sms_request (phone);
CREATE INDEX idx_sms_request_otp_expires ON sms_request (otp_expires_at);

CREATE TABLE sms_attempt (
  id                  bigserial PRIMARY KEY,
  sms_request_id      uuid NOT NULL REFERENCES sms_request(id) ON DELETE CASCADE,
  attempt_no          int NOT NULL,
  started_at          timestamptz NOT NULL,
  finished_at         timestamptz,
  result              varchar(16) NOT NULL CHECK (result IN ('SUCCESS','FAIL')),
  error_code          varchar(64),
  error_message       text,
  provider_message_id text,
  latency_ms          int
);

CREATE INDEX idx_sms_attempt_request ON sms_attempt (sms_request_id, attempt_no);
```

---

## 6. Модель статусов

Статусы `sms_request.status`:

- `IN_PROGRESS` — создан, ожидает или выполняет отправку, есть ретраи;
- `SUCCESS` — доставка подтверждена провайдером;
- `NOT_SEND` — окончательный отказ после исчерпания `max_attempts`.

Переходы:

```
POST            → IN_PROGRESS
IN_PROGRESS     → SUCCESS       (провайдер ok)
IN_PROGRESS     → IN_PROGRESS   (провайдер fail, attempts < max)
IN_PROGRESS     → NOT_SEND      (провайдер fail, attempts >= max)
```

Запрещённые переходы (`SUCCESS → *`, `NOT_SEND → *`) валидируются на уровне сервиса.

---

## 7. API

### 7.1 `POST /api/v1/sms`

Заголовки: `Idempotency-Key: <uuid>` (обязателен), `Authorization: Bearer <service-jwt>` (service-to-service) или публичный доступ (rate limit по телефону и IP).

Body:
```json
{ "phone": "+79991234567" }
```

Ответ `202 Accepted` (создано) или `200 OK` (повтор с тем же ключом и payload):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "IN_PROGRESS",
  "attempts": 0,
  "createdAt": "2026-01-01T10:00:00Z",
  "sentAt": null,
  "expiresAt": "2026-01-01T10:05:00Z"
}
```

Ошибки:

| Код | Причина |
|---|---|
| 400 | отсутствует `Idempotency-Key` |
| 409 | тот же ключ, другой payload (phone) |
| 422 | невалидный номер |
| 429 | rate limit по телефону или IP |

### 7.2 `GET /api/v1/sms/{id}`

Ответ `200`:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "SUCCESS",
  "attempts": 1,
  "createdAt": "2026-01-01T10:00:00Z",
  "sentAt": "2026-01-01T10:00:03Z",
  "expiresAt": "2026-01-01T10:05:00Z"
}
```
`404` — если не найдено.

### 7.3 `POST /api/v1/sms/{id}/verify`

**Доступ:** только service-to-service (JWT HS256, общий `JWT_SECRET`). Заголовок `Authorization: Bearer <service-jwt>`.

Body:
```json
{ "code": "123456" }
```

Ответ `200`:
```json
{ "verified": true }
```
```json
{ "verified": false, "reason": "INVALID_CODE" }
```
`reason` ∈ {`INVALID_CODE`, `EXPIRED`, `TOO_MANY_ATTEMPTS`}.

Ошибки:

| Код | Причина |
|---|---|
| 401 | невалидный/отсутствующий service JWT |
| 404 | запись не найдена |
| 409 | статус SMS `IN_PROGRESS` / `NOT_SEND` |

### 7.4 `GET /actuator/health`, `GET /actuator/prometheus`

Стандартные endpoints Spring Boot Actuator. Health проверяет: DB, Redis, провайдер (в профиле `prod` — connectivity check).

---

## 8. Бизнес-логика

### 8.1 Определение языка

```java
@Component
public class PhoneLanguageResolver {
    private final List<String> cisPrefixes;

    public PhoneLanguageResolver(SmsProperties props) {
        this.cisPrefixes = props.cisPrefixes().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    }

    public Language resolve(String e164) {
        return cisPrefixes.stream().anyMatch(e164::startsWith)
            ? Language.RU : Language.EN;
    }
}
```

Нормализация номера — через `libphonenumber`:
`PhoneNumberUtil.parse(raw, null).format(E164)`. `NumberParseException` → `422`.

### 8.2 Генерация и форматирование

```java
@Component
public class OtpGenerator {
    private final SecureRandom random = new SecureRandom();
    public String generate() {
        return String.format("%06d", random.nextInt(1_000_000));
    }
}

@Component
public class MessageFormatter {
    private static final Map<Language, String> TEMPLATES = Map.of(
        Language.RU, "Код подтверждения: %s",
        Language.EN, "Verification code: %s"
    );
    private static final Map<Language, Integer> LIMITS = Map.of(
        Language.RU, 60, Language.EN, 140
    );

    public String format(Language lang, String code) {
        String msg = TEMPLATES.get(lang).formatted(code);
        int len = msg.codePointCount(0, msg.length());
        if (len > LIMITS.get(lang)) {
            throw new IllegalStateException(
                "Template exceeds limit for " + lang + ": " + len);
        }
        return msg;
    }
}
```

Нарушение — `IllegalStateException` → `500` через `GlobalExceptionHandler` + метрика с алертом.

### 8.3 SHA-256 утилита (замена Argon2)

```java
@Component
public class OtpHasher {
    private static final String ALGORITHM = "SHA-256";

    public String hash(String code) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            byte[] digest = md.digest(code.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public boolean verify(String code, String hash) {
        String computed = hash(code);
        return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

### 8.4 Провайдер SMS — профили sim / prod

```java
public interface SmsProvider {
    SendResult send(SmsRequest req);
}

public record SendResult(boolean success, String providerMessageId, String errorCode) {
    public static SendResult success(String id) { return new SendResult(true, id, null); }
    public static SendResult failure(String err) { return new SendResult(false, null, err); }
}
```

**Профиль `sim` (по умолчанию):**

```java
@Component
@Profile("sim")
public class SimulatedSmsProvider implements SmsProvider {
    private final Random random;
    private final double successRate;
    private final MeterRegistry metrics;

    public SimulatedSmsProvider(SmsProperties props,
                                @Qualifier("providerRandom") Random random,
                                MeterRegistry metrics) {
        this.random = random;
        this.successRate = props.simulatedSuccessRate();
        this.metrics = metrics;
    }

    @Override
    public SendResult send(SmsRequest req) {
        boolean ok = random.nextDouble() < successRate;
        metrics.counter("sms_provider_result_total", "result", ok ? "success" : "fail").increment();
        return ok
            ? SendResult.success(UUID.randomUUID().toString())
            : SendResult.failure("SIMULATED_DELIVERY_FAILED");
    }
}
```

**Профиль `prod` (адаптер к общему SmsSender):**

```java
@Component
@Profile("prod")
@RequiredArgsConstructor
public class RealSmsProvider implements SmsProvider {
    private final SmsSender smsSender;           // из common-sms библиотеки
    private final MeterRegistry metrics;

    @Override
    public SendResult send(SmsRequest req) {
        long start = System.nanoTime();
        try {
            smsSender.send(req.getPhone(), req.getMessage());
            int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);
            String providerMsgId = "sent-" + UUID.randomUUID(); // real provider returns ID
            metrics.counter("sms_provider_result_total", "result", "success").increment();
            metrics.timer("sms_provider_latency_seconds").record(latencyMs, TimeUnit.MILLISECONDS);
            return SendResult.success(providerMsgId);
        } catch (SmsDeliveryException e) {
            int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);
            metrics.counter("sms_provider_result_total", "result", "fail").increment();
            metrics.timer("sms_provider_latency_seconds").record(latencyMs, TimeUnit.MILLISECONDS);
            return SendResult.failure(e.getErrorCode());
        }
    }
}
```

> `SmsSender` интерфейс и реализации (`VonageSmsSender`, `TwilioSmsSender`, `LoggingSmsSender`) вынесены в **shared library `common-sms`** (отдельный Gradle модуль), используемый основным бэкендом и SMS-сервисом.

### 8.5 Redis OTP Store + Lock

```java
@Component
@RequiredArgsConstructor
public class RedisOtpStore {
    private final RedisTemplate<String, Object> redis;
    private final SmsProperties props;

    private static final String OTP_KEY = "sms:otp:%s";           // hash {hash, phone, attempts}
    private static final String USED_KEY = "sms:otp:used:%s";     // string "1", TTL 24h
    private static final String LOCK_KEY = "sms:lock:phone:%s";   // string "1", TTL 10s

    public boolean tryLockPhone(String e164) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
            LOCK_KEY.formatted(e164), "1", Duration.ofSeconds(10)));
    }

    public void releaseLockPhone(String e164) {
        redis.delete(LOCK_KEY.formatted(e164));
    }

    public void saveOtp(UUID smsId, String hash, String phone) {
        Map<String, Object> data = Map.of("hash", hash, "phone", phone, "attempts", 0);
        redis.opsForHash().putAll(OTP_KEY.formatted(smsId), data);
        redis.expire(OTP_KEY.formatted(smsId), props.otpTtl());
    }

    public Optional<OtpData> getOtp(UUID smsId) {
        Map<Object, Object> entries = redis.opsForHash().entries(OTP_KEY.formatted(smsId));
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new OtpData(
            (String) entries.get("hash"),
            (String) entries.get("phone"),
            ((Number) entries.get("attempts")).intValue()
        ));
    }

    public boolean incrementAttempts(UUID smsId) {
        Long attempts = redis.opsForHash().increment(OTP_KEY.formatted(smsId), "attempts", 1);
        return attempts != null && attempts <= 5;
    }

    public void markUsed(UUID smsId) {
        redis.delete(OTP_KEY.formatted(smsId));
        redis.opsForValue().set(USED_KEY.formatted(smsId), "1", Duration.ofHours(24));
    }

    public boolean isUsed(UUID smsId) {
        return Boolean.TRUE.equals(redis.hasKey(USED_KEY.formatted(smsId)));
    }

    public record OtpData(String hash, String phone, int attempts) {}
}
```

### 8.6 Создание SMS (используется и REST, и внутренние вызовы)

```java
@Service
@RequiredArgsConstructor
public class SmsService {
    private final SmsRequestRepository repo;
    private final PhoneNormalizer phoneNormalizer;
    private final PhoneLanguageResolver languageResolver;
    private final OtpGenerator otpGenerator;
    private final MessageFormatter messageFormatter;
    private final OtpHasher otpHasher;
    private final RedisOtpStore redisOtpStore;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    @Transactional
    public CreateResult create(String idempotencyKey, String rawPhone) {
        String phone = phoneNormalizer.toE164(rawPhone);
        
        // Rate limit by phone (Redis counter)
        checkPhoneRateLimit(phone);

        var existing = repo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getPhone().equals(phone))
                throw new ConflictException("Idempotency key used with different phone");
            return CreateResult.existing(existing.get());
        }

        // Lock phone to prevent concurrent OTP generation
        if (!redisOtpStore.tryLockPhone(phone)) {
            throw new RateLimitException("Phone busy, retry later");
        }
        try {
            String code = otpGenerator.generate();
            Language lang = languageResolver.resolve(phone);
            String message = messageFormatter.format(lang, code);
            String otpHash = otpHasher.hash(code);

            SmsRequest req = SmsRequest.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(idempotencyKey)
                .phone(phone)
                .language(lang)
                .message(message)
                .status(SmsStatus.IN_PROGRESS)
                .attempts(0)
                .maxAttempts(props.maxAttempts())
                .nextAttemptAt(Instant.now())
                .otpHash(otpHash)
                .otpExpiresAt(Instant.now().plus(props.otpTtl()))
                .maxVerifyAttempts(props.maxVerifyAttempts())
                .build();

            try {
                repo.save(req);
            } catch (DataIntegrityViolationException dup) {
                return CreateResult.existing(
                    repo.findByIdempotencyKey(idempotencyKey).orElseThrow());
            }

            // Save to Redis for fast verification
            redisOtpStore.saveOtp(req.getId(), otpHash, phone);

            metrics.counter("sms_created_total", "language", lang.name()).increment();
            return CreateResult.created(req);
        } finally {
            redisOtpStore.releaseLockPhone(phone);
        }
    }

    private void checkPhoneRateLimit(String phone) {
        String key = "ratelimit:phone:" + phone;
        Long count = redis.opsForValue().increment(key);
        if (count == 1) redis.expire(key, Duration.ofMinutes(1));
        if (count > props.rateLimit().phonePerMinute()) {
            throw new RateLimitException("Phone rate limit exceeded");
        }
    }
}
```

### 8.7 Верификация

```java
@Service
@RequiredArgsConstructor
public class SmsVerifyService {
    private final SmsRequestRepository repo;
    private final RedisOtpStore redisOtpStore;
    private final OtpHasher otpHasher;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    @Transactional
    public VerifyResult verify(UUID id, String code) {
        // Idempotent repeat after success
        if (redisOtpStore.isUsed(id)) return VerifyResult.success();

        SmsRequest req = repo.findById(id).orElseThrow(NotFoundException::new);

        if (req.getStatus() != SmsStatus.SUCCESS) return VerifyResult.notReady();
        if (req.getVerifiedAt() != null) return VerifyResult.success();
        if (Instant.now().isAfter(req.getOtpExpiresAt())) return VerifyResult.expired();
        if (req.getVerifyAttempts() >= req.getMaxVerifyAttempts())
            return VerifyResult.tooManyAttempts();

        // Atomic increment in PostgreSQL
        int updated = repo.incrementVerifyAttempts(id, req.getMaxVerifyAttempts());
        if (updated == 0) return VerifyResult.tooManyAttempts();

        // Verify against Redis first (hot path), fallback to PG
        String hash = redisOtpStore.getOtp(id)
            .map(RedisOtpStore.OtpData::hash)
            .orElse(req.getOtpHash());

        if (!otpHasher.verify(code, hash)) {
            metrics.counter("sms_verify_result_total", "reason", "INVALID_CODE").increment();
            return VerifyResult.invalid();
        }

        // Success
        repo.markVerified(id);
        redisOtpStore.markUsed(id);
        metrics.counter("sms_verify_result_total", "reason", "SUCCESS").increment();
        return VerifyResult.success();
    }
}
```

```java
@Modifying
@Query("""
  UPDATE SmsRequest r SET r.verifyAttempts = r.verifyAttempts + 1
  WHERE r.id = :id AND r.verifyAttempts < :max
""")
int incrementVerifyAttempts(@Param("id") UUID id, @Param("max") int max);

@Modifying
@Query("""
  UPDATE SmsRequest r SET r.verifiedAt = now(), r.updatedAt = now()
  WHERE r.id = :id
""")
void markVerified(@Param("id") UUID id);
```

---

## 9. Cron-джобы

### 9.1 Dispatch (как в спеку v1.0, без изменений)

```java
@Component
@RequiredArgsConstructor
public class SmsDispatchJob {
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private final SmsRequestRepository repo;
    private final SmsAttemptRepository attemptRepo;
    private final SmsProvider smsProvider;
    private final TransactionTemplate txTemplate;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    @Scheduled(fixedDelayString = "PT5S")
    public void dispatch() {
        List<UUID> ids = repo.claimBatch(
            INSTANCE_ID, props.dispatchBatchSize(), props.lockLease());
        
        metrics.gauge("sms_dispatch_batch_size", ids.size());
        
        for (UUID id : ids) {
            try {
                processOne(id);
            } catch (Exception e) {
                metrics.counter("sms_dispatch_error_total").increment();
                log.error("dispatch failed id={}", id, e);
            }
        }
    }

    private void processOne(UUID id) {
        SmsRequest req = repo.findById(id).orElseThrow();
        long start = System.nanoTime();
        SendResult result;
        try {
            result = smsProvider.send(req);
        } catch (Exception e) {
            result = SendResult.failure("PROVIDER_EXCEPTION");
        }
        int latencyMs = (int) ((System.nanoTime() - start) / 1_000_000);

        txTemplate.executeWithoutResult(tx -> {
            repo.finishAttempt(id, result, latencyMs, backoffFor(req.getAttempts()));
            attemptRepo.save(SmsAttempt.of(req, result, latencyMs));
        });
    }

    private Instant backoffFor(int attempts) {
        return switch (attempts) {
            case 1 -> Instant.now().plus(props.firstBackoff());
            case 2 -> Instant.now().plus(props.secondBackoff());
            default -> Instant.now().plus(props.secondBackoff());
        };
    }
}
```

`claimBatch` (native SQL, Hibernate 7 SKIP LOCKED только через native):

```sql
WITH claimed AS (
  SELECT id FROM sms_request
  WHERE status = 'IN_PROGRESS'
    AND attempts < max_attempts
    AND next_attempt_at <= now()
    AND (locked_until IS NULL OR locked_until < now())
  ORDER BY next_attempt_at
  LIMIT :batch
  FOR UPDATE SKIP LOCKED
)
UPDATE sms_request r
SET locked_until = now() + :lease::interval,
    locked_by = :instance,
    attempts = r.attempts + 1,
    updated_at = now()
FROM claimed
WHERE r.id = claimed.id
RETURNING r.id
```

`finishAttempt`:

```sql
UPDATE sms_request r SET
  status = CASE WHEN :ok THEN 'SUCCESS'
                WHEN r.attempts >= r.max_attempts THEN 'NOT_SEND'
                ELSE 'IN_PROGRESS' END,
  sent_at = CASE WHEN :ok THEN now() ELSE r.sent_at END,
  next_attempt_at = CASE WHEN :ok OR r.attempts >= r.max_attempts THEN NULL
                         ELSE :nextAttempt END,
  last_error = :error,
  provider_message_id = COALESCE(:providerId, r.provider_message_id),
  locked_until = NULL,
  locked_by = NULL,
  updated_at = now()
WHERE r.id = :id
```

Правила:
- `claimBatch` — в короткой транзакции, только захват.
- Вызов провайдера — **вне** транзакции.
- `finishAttempt` + вставка `sms_attempt` — в короткой транзакции.
- `fixedDelay`, не `fixedRate` — иначе батчи накладываются.
- При падении инстанса лиз истекает через 30 с, запись подхватывается другим.
- At-least-once: при сбое между `send()` и `finishAttempt` возможна повторная отправка того же кода — это допустимо для OTP.

### 9.2 Retention — две джобы

```java
@Component
@RequiredArgsConstructor
public class SmsRetentionJob {
    private final SmsRequestRepository requestRepo;
    private final SmsAttemptRepository attemptRepo;
    private final SmsProperties props;
    private final MeterRegistry metrics;

    @Scheduled(cron = "0 0 * * * *")  // каждый час
    @Transactional
    public void cleanupRequests() {
        Instant cutoff = Instant.now().minus(props.retentionRequest());
        int deleted = requestRepo.deleteOlderThan(cutoff);
        metrics.counter("sms_retention_deleted_total", "table", "sms_request").increment(deleted);
        log.info("retention cleanup sms_request: {} rows", deleted);
    }

    @Scheduled(cron = "0 5 * * * *")  // каждый час + 5 мин
    @Transactional
    public void cleanupAttempts() {
        Instant cutoff = Instant.now().minus(props.retentionAttempt());
        int deleted = attemptRepo.deleteOlderThan(cutoff);
        metrics.counter("sms_retention_deleted_total", "table", "sms_attempt").increment(deleted);
        log.info("retention cleanup sms_attempt: {} rows", deleted);
    }
}
```

```java
@Modifying
@Query("DELETE FROM SmsRequest r WHERE r.createdAt < :cutoff")
int deleteOlderThan(@Param("cutoff") Instant cutoff);

@Modifying
@Query("DELETE FROM SmsAttempt a WHERE a.startedAt < :cutoff")
int deleteOlderThan(@Param("cutoff") Instant cutoff);
```

---

## 10. Безопасность

- `POST /api/v1/sms` — публичный (или за общим API-gateway) + rate limit по телефону (Redis) и IP (nginx).
- `GET /api/v1/sms/{id}` — публичный.
- `POST /api/v1/sms/{id}/verify` — **только service-to-service** (JWT HS256, общий `JWT_SECRET`).

Spring Security 7, лямбда-DSL:

```java
@Configuration
@EnableConfigurationProperties(SmsProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/v1/sms", "/api/v1/sms/*")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    SecurityFilterChain verifyChain(HttpSecurity http, JwtProperties jwtProps) throws Exception {
        http
            .securityMatcher("/api/v1/sms/*/verify")
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder(jwtProps))))
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("ADMIN"));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties props) {
        return NimbusJwtDecoder.withSecretKey(
            Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8))).build();
    }

    @ConfigurationProperties(prefix = "app.jwt")
    public record JwtProperties(String secret) {}
}
```

Правила хранения и логирования:
- **Не логировать** `message` и `code`.
- Телефон маскировать: `+7999***4567` (утилита `PhoneMask` из `common-sms`).
- `otp_hash` — SHA-256 hex (не Argon2).
- `message` хранится открытым (по требованию), но удаляется по retention (7 дней).
- Секреты — через переменные окружения.

---

## 11. Наблюдаемость

Метрики Micrometer (Prometheus):

| Метрика | Тип | Описание |
|---|---|---|
| `sms_created_total{language}` | counter | созданные запросы |
| `sms_status_transition_total{from,to}` | counter | переходы статусов |
| `sms_provider_result_total{result}` | counter | результат провайдера |
| `sms_dispatch_batch_size` | gauge | размер захваченного батча |
| `sms_dispatch_lag_seconds` | gauge | `now - min(next_attempt_at)` для `IN_PROGRESS` |
| `sms_verify_result_total{reason}` | counter | результаты верификации |
| `sms_retention_deleted_total{table}` | counter | удалённые записи |
| `sms_provider_latency_seconds` | timer | латентность вызова провайдера |
| `sms_rate_limited_total{type}` | counter | rate limit срабатывания (phone/ip) |

Логи: structured JSON, поля `request_id`, `sms_id`, `status`, `attempt`, `latency_ms`, `masked_phone`, `error_code`. Поля `message` и `code` отсутствуют.

Health: `/actuator/health` — проверяет DB, Redis, провайдер (в `prod` профиле).

Алерты:
- `sms_dispatch_lag_seconds > 60` — cron не успевает;
- Рост `sms_status_transition_total{to="NOT_SEND"}` — деградация провайдера;
- `500` от сервиса — проверка инварианта шаблона (недостижимо при корректном конфиге).

---

## 12. Тестирование

### Unit

- `PhoneLanguageResolver`: `+7/+375/+380/+995` → RU, `+1/+44` → EN; проверка порядка префиксов (длинный раньше).
- `MessageFormatter`: длина ≤ 60/140 для сгенерированного кода; превышение → `IllegalStateException`.
- `OtpGenerator`: 6 цифр, включая ведущие нули (`000123`).
- `OtpHasher`: SHA-256 hex, constant-time verify.
- `SimulatedSmsProvider` с `Random(seed)` — ~70% на 10 000 итерациях.
- `RealSmsProvider` — мокает `SmsSender`, проверяет успех/ошибку.
- `RedisOtpStore`: lock, save, get, increment, markUsed, isUsed.
- `SmsVerifyService`: все `reason` (INVALID_CODE, EXPIRED, TOO_MANY_ATTEMPTS, идемпотентный повтор).

### Integration (Testcontainers 2.x, PostgreSQL + Redis)

- Идемпотентность: 2× POST с одинаковым ключом → одна запись, второй `200`.
- `409` при том же ключе и другом номере.
- `422` невалидный номер.
- `429` rate limit по телефону.
- Верификация: успех / неверный код / истёк / too many / повторный `200 verified:true`.
- Верификация на `IN_PROGRESS` → `409`.
- Dispatch: success с 1-й, fail+success на 2-й, fail×3 → `NOT_SEND`.
- Два инстанса `SmsDispatchJob` параллельно — без двойной отправки (проверка по `sms_attempt`).
- Retention: `sms_request` старше 7 дней удалены, `sms_attempt` старше 30 дней удалены.
- Профили: `sim` использует `SimulatedSmsProvider`, `prod` использует `RealSmsProvider`.

### Особенности Spring Boot 4

- `@MockitoBean` вместо `@MockBean` / `@SpyBean`.
- `PostgreSQLContainer` из `org.testcontainers.postgresql`, `GenericContainer` для Redis.
- `RestTestClient` вместо `TestRestTemplate` для интеграционных тестов API.
- Jackson 3: настройка `ObjectMapper` может потребовать правки пакетов (`tools.jackson.*`).

### Нагрузочное

- 2 инстанса `SmsDispatchJob` на 10 000 `IN_PROGRESS` записей — отсутствие дублей, отсутствие зависших записей через 2× lease.
- Rate limit: 1000 запросов/с на `POST /api/v1/sms` — корректная работа Redis counter.

---

## 13. Соответствие Spring Boot 4 / Java 25

| Область | Решение |
|---|---|
| `@Scheduled` | `fixedDelay`, размер пула планировщика ≥ 2 |
| Виртуальные потоки | `spring.threads.virtual.enabled=true` |
| `SKIP LOCKED` | только native SQL (Hibernate 7) |
| Spring Security 7 | лямбда-DSL, `securityMatcher`, JWT HS256 |
| Jackson 3 | учитывать breaking changes в `ObjectMapper` |
| Модульные стартеры | `spring-boot-restclient` при появлении HTTP-провайдера |
| Тесты | `@MockitoBean`, Testcontainers 2.x, `RestTestClient` |
| Null-safety | JSpecify, явные `@Nullable` при необходимости |
| Actuator | `org.springframework.boot.health.contributor.*` для кастомных health-индикаторов |
| `PasswordEncoder` | **не используется** — свой `OtpHasher` (SHA-256) |

---

## 14. Структура пакетов

```
com.example.sms
├── api
│   ├── SmsController
│   ├── SmsVerifyController
│   ├── dto
│   │   ├── CreateSmsRequest
│   │   ├── SmsResponse
│   │   ├── VerifyRequest
│   │   └── VerifyResponse
│   └── error
│       ├── GlobalExceptionHandler
│       ├── ApiError
│       ├── ConflictException
│       ├── NotFoundException
│       ├── ValidationException
│       └── RateLimitException
├── domain
│   ├── SmsRequest
│   ├── SmsAttempt
│   ├── SmsStatus
│   ├── Language
│   ├── SmsRequestRepository
│   └── SmsAttemptRepository
├── service
│   ├── SmsService
│   ├── SmsVerifyService
│   ├── OtpGenerator
│   ├── MessageFormatter
│   ├── PhoneLanguageResolver
│   ├── PhoneNormalizer
│   └── OtpHasher
├── provider
│   ├── SmsProvider
│   ├── SimulatedSmsProvider
│   ├── RealSmsProvider
│   └── SendResult
├── worker
│   └── SmsDispatchJob
├── cleanup
│   └── SmsRetentionJob
├── config
│   ├── SmsProperties
│   ├── SecurityConfig
│   ├── SchedulerConfig
│   └── RedisConfig
└── util
    ├── PhoneMask (из common-sms)
    └── RateLimitFilter
```

---

## 15. Чек-лист реализации

- [ ] Flyway `V1__sms_schema.sql` (таблицы + индексы + CHECK constraints)
- [ ] `SmsProperties` + `application.yml` (профили sim/prod, retention 7d/30d, rate limits)
- [ ] `SchedulerConfig` (`@EnableScheduling`, `providerRandom`, `TransactionTemplate`)
- [ ] `PhoneNormalizer` (libphonenumber, E.164)
- [ ] `PhoneLanguageResolver`
- [ ] `OtpGenerator`
- [ ] `MessageFormatter` (инвариант длины)
- [ ] `OtpHasher` (SHA-256, constant-time)
- [ ] `RedisOtpStore` (lock, save, get, increment, markUsed, isUsed)
- [ ] `SimulatedSmsProvider` (`@Profile("sim")`)
- [ ] `RealSmsProvider` (`@Profile("prod")`, адаптер к `SmsSender` из common-sms)
- [ ] `SmsService.create` (идемпотентность, rate limit, Redis lock, dual write PG + Redis)
- [ ] `SmsVerifyService.verify` (Redis first, fallback PG, атомарный инкремент, идемпотентность)
- [ ] `SmsDispatchJob` + `claimBatch` + `finishAttempt` (cron, SKIP LOCKED)
- [ ] `SmsRetentionJob` (две джобы: 7d requests, 30d attempts)
- [ ] `GlobalExceptionHandler` (400 / 404 / 409 / 422 / 429 / 500)
- [ ] `SecurityConfig` (Spring Security 7 DSL, JWT HS256 на `/verify`)
- [ ] Метрики Micrometer + маскирование в логах (`PhoneMask`)
- [ ] Testcontainers + unit-тесты (раздел 12)
- [ ] Нагрузочный тест (2 инстанса, 10 000 записей)
- [ ] **Shared library `common-sms`** вынесена в отдельный Gradle модуль

---

## 16. Открытые вопросы и допущения

- Симуляция провайдера: 70% успеха **на каждую попытку**, ретраи разрешены; итоговый SUCCESS ≈ 97.3%, `NOT_SEND` ≈ 2.7%.
- Список префиксов СНГ задаётся конфигом; `+380` (UA) и `+995` (GE) включены по решению; `+375` (BY) → RU.
- Текст SMS хранится открытым; OTP — SHA-256 в `otp_hash`; `message` удаляется по retention (7 дней).
- Retention: 7 дней для `sms_request`, 30 дней для `sms_attempt` (разные джобы).
- Повторная верификация после успеха идемпотентна (`200 verified:true`) через `sms:otp:used:{id}` в Redis.
- `422` при превышении длины недостижим на входе (текст генерирует сервис); инвариант проверяется и при нарушении → `500`.
- При падении инстанса между `send()` и `finishAttempt` возможна повторная отправка того же OTP — допустимо.
- `max_attempts=3`, backoff 15/30 с, cron 5 с — выбраны для пет-проекта; параметры конфигурируемы.
- **Shared library `common-sms`** — отдельный модуль, публикуется в локальный Maven / GitHub Packages, используется основным бэкендом и SMS-сервисом.
- Rate limit: телефон — Redis counter в SMS-сервисе; IP — nginx `limit_req_zone` (не в коде сервиса).