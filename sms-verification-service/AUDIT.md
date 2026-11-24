# Аудит `sms-verification-service`

Дата аудита: 2026-11-22. Текущее состояние: 2026-11-23 (после восстановления из git-объектов).
Метод: разбор кода, миграций, конфигурации, docker-compose/nginx и тестов; затем проверка
`./gradlew test` (unit) и `./gradlew integrationTest` (Testcontainers: PostgreSQL/Redis/RabbitMQ).

## Что произошло с рабочей копией

Вчерашняя работа не была закоммичена: при восстановлении из `origin/dev` пакет
`dev.nosurprise.sms` и все миграции V3–V9 исчезли из рабочего дерева. Исходники удалось достать из
объектной базы git (файлы были проиндексированы, но не закоммичены): 92 java-файла, миграции V3–V9,
конфигурация. Два класса (`SmsService`, `SmsDispatchJob`) и `SecurityConfig` объектов не имели и
восстановлены по контрактам тестов; `application.yml`, `build.gradle`, wrapper и docker-compose
собраны заново. `AUDIT.md` переписан (в git его не было — файл лежал вне индекса).

## Итог: 7 дефектов P0 и 20 пунктов P1 закрыты

Главная системная проблема аудита — dispatch-джоба потеряла lease-механизм при миграции V4 —
закрыта. Восстановленное состояние дополнительно содержит то, чего в аудите ещё не было:
**transactional outbox** для доставки кода в Telegram (V9) и **маскирование OTP** в
`sms_request.message` (V8).

### P0

1. **1.1 Lease и backoff.** `V6__sms_lease_and_expiry.sql` добавляет `last_attempt_at`, `locked_until`,
   `locked_by`, `expires_at`, `verified_at`, `updated_at`. `claimBatch` берёт строки под lease
   (`FOR UPDATE SKIP LOCKED`), backoff считается от `last_attempt_at`, lease продлевается перед каждой
   отправкой (`renewLease`); потеря lease = строка не отправляется повторно.
2. **1.2 Финальный статус.** `finishAttempt` двигает статус только из `CREATED`; `NOT_DELIVERED`
   ставится лишь после исчерпания попыток, промежуточная неудача возвращает строку в очередь.
3. **1.3 Идемпотентность.** Проверка `Idempotency-Key` идёт до лимита; `saveAndFlush` + обработка
   `DataIntegrityViolationException` в контроллере дают 200 на гонку; тот же ключ с другим номером — 409.
4. **1.4 Лимит на номер.** Lua-скрипт `INCR`+`EXPIRE` атомарен (ключ не останется без TTL),
   лимит проверяется после идемпотентности, поэтому ретрай не получает 429.
5. **1.5 Доступ.** `/api/v1/sms/**` требует service-JWT с `sub=sms-client`: отдельный `JwtDecoder`
   с валидатором subject, чужой токен получает 401 (а не 403).
6. **1.6 Провайдер.** Один переключатель `app.sms.provider` (`sim|vonage|twilio|log`), префиксы
   `app.sms.vonage`/`app.sms.twilio` совпадают с `@ConfigurationProperties`, дублей бинов нет,
   `provider=log` работает. Закрыто вместе с восстановлением конфигурации.
7. **1.7 TTL и истечение.** `expires_at` пишется при создании, `expireStale` гасит просроченные коды
   (но не строки с живой lease), `SmsVerifyService` проверяет срок до сверки кода.

### P1

2.1 service-JWT на создании SMS (IP-лимит не нужен и удалён); 2.2 таймауты `RestClient`/Bot API;
2.3 токен бота не попадает в лог; 2.4–2.5 claim и отправка вне транзакции; 2.6–2.8 `@NotBlank`,
401 на не-UUID subject, 404 вместо пустого статуса, `codeSent` из `SmsStatus`; 2.9 `closeExhausted`
закрывает «зомби»; 2.10–2.11 polling по умолчанию выключен, дедуп не теряет retry; 2.12 `UserSyncConsumer`
подписан только на `user.phone.changed`/`user.blocked`; 2.13 `flyway.validate-on-migrate: true`,
`allow-bean-definition-overriding` убран; 2.14 общий `OtpChallengeService` для обоих каналов;
2.15 deny-by-default (`anyRequest().denyAll()`); 2.16 retention чистит `verification_requests`;
2.17 `PhoneNormalizer` проверяет `isValidNumber`; 2.18 тело SMS не логируется; 2.19 healthcheck —
`/actuator/health/readiness`; 2.20 единый TTL `sms.otp-ttl` (удалены `app.verification.*`).

## Что изменилось по сравнению с исходным аудитом

### OTP больше не лежит в БД открытым текстом (V8)

`sms_request.message` хранит шаблон с маской (`Код подтверждения: ******`), открытый код живёт
только в Redis (`SmsCodeHandoffStore`, TTL = `sms.otp-ttl`) и нужен лишь для передачи во второй канал.
Текст SMS рендерится в момент отправки (`MessageFormatter` + handoff). Поэтому «прочитать код из БД»
больше нельзя — тесты берут код из Redis.

### Доставка в Telegram развязана через outbox (V9)

Прямой вызов Telegram из dispatch-джобы убран: успешная отправка SMS в той же транзакции пишет
`sms.outbox_event` (`insertIfPendingVerification` — только если для этого SMS есть ожидающая
верификация). `OutboxRelayJob` опрашивает таблицу с lease и backoff, доставляет код и различает
исходы (`DELIVERED`, `NO_OP`, `RETRYABLE`, `PERMANENT`), после 10 попыток помечает событие `DEAD`.
Плюс периодический `reconcileMissingDelivered` — лечит пропущенные события («SMS доставлена,
верификация ждёт»). Так закрыт сценарий «SMS ушла, Telegram недоступен, второго триггера нет».

### Dispatch-батч параллельный

Строки пачки отправляются виртуальными потоками с ограничением параллелизма 8: поток планировщика
не блокируется суммарным таймаутом провайдера, но шлюз не получает шквал из 100 запросов.
Мотив и альтернативы — `docs/ADR-001-dispatch-via-db-polling.md`.

### Чистка и архитектура

Удалены `RedisOtpStore`, `RedisConfig`, `DispatchSmsProvider`, `RealSmsProvider`, `LoggingSmsSenderConfig`,
`VerificationProperties`, дубли индексов и `sms_attempt_seq` (V7); DTO — records; OTP-хеш без телефона;
Gradle wrapper 9.2.1. `sms_attempt` оставлена: она пишется на каждую попытку и является единственной
историей «какая попытка чем кончилась» (`sms_request.attempts` — только счётчик).

## Тесты

Unit: `OtpChallengeService`, `SmsVerifyService`, `SmsDispatchJob`, `OutboxRelayJob`, `SmsRetentionJob`,
`UserSyncConsumer`, `SmsConfigProdTest` (единственный транспорт и отсутствие дублей), `CircuitBreakerSmsSender`,
`MessageFormatter`, `OtpGenerator`/`OtpHasher`, `PhoneNormalizer`, Telegram-слой, фильтр webhook.
Integration (Testcontainers): создание/идемпотентность/лимит/статус, отправка с lease и backoff,
NOT_DELIVERED после попыток, «зомби», проверка кода и сгорание после 3 неверных, 401 на чужой/пустой JWT,
404 на неизвестный путь, миграции V1–V9.

Проверено: `./gradlew test` и `./gradlew integrationTest` — BUILD SUCCESSFUL.

## Осталось / требует вашего решения

* Живой сквозной прогон backend → sms-verification-service → Telegram: нужен `TELEGRAM_BOT_TOKEN`,
  без него канал не проверить (unit/integration покрывают логику, но не сеть Telegram).
* `sms_attempt` по-прежнему не хранит диагностику (`latency_ms`, `error_code`, `provider_message_id`):
  на вопрос «почему не доставлено» таблица не отвечает.
