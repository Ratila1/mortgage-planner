# CONTRACT.md — Notification & Verification Service (Telegram 2FA)

Документ-контракт для будущей реализации. Скелет уже создан (`notification-service/`).
Ничего здесь не является рабочим кодом «из коробки» — это спецификация того, как сервис должен быть устроен.

## 0. Общие сведения

- **Имя сервиса:** `notification-service`
- **Порт:** `8090` (env `SERVER_PORT`)
- **Пакет:** `dev.nosurprise.notificationservice`
- **Стек:** Spring Boot 4.0.9, Java 25 (виртуальные потоки), Gradle Groovy DSL — как в основном бэкенде (`backend/`).
- **БД:** PostgreSQL 17, отдельная база `nosurprise_notifications`, Flyway (`ddl-auto: validate`).
- **Хранилища:** Redis 7 (OTP, rate limit, идемпотентность, блокировки), RabbitMQ 4 (общий exchange `domain.events`).
- **Внешняя зависимость:** Telegram Bot API (`https://api.telegram.org/bot<TOKEN>`).
- **Аутентификация пользователей:** общий `JWT_SECRET` (HS256, Nimbus) с основным бэкендом — микросервис валидирует access-токен сам.
- **Межсервисная аутентификация:** заголовок `X-Service-Token: ${SERVICE_TOKEN}` при вызовах основного бэкенда.

## 1. Зоны ответственности

1. Минималистичный Telegram-бот: команда `/start` → кнопка «Поделиться номером» (`ReplyKeyboardMarkup` с `request_contact = true`) → проверка контакта → привязка `telegram_id` к пользователю по номеру телефона.
2. Приём события `verification.requested` от основного бэкенда → генерация 6-значного кода → отправка в Telegram → хранение хэша кода в Redis.
3. Приём кода от пользователя (фронт → микросервис напрямую) → проверка → синхронный вызов основного бэкенда `POST /api/internal/verifications/{id}/complete`.
4. Синхронизация данных пользователя (phone changed, blocked) через RabbitMQ.

Старый функционал подтверждения телефона по коду из SMS в основном бэкенде (`PhoneVerificationService`, `/api/me/phone/verification`) **удаляется**; подтверждение телефона теперь происходит через привязку Telegram.

## 2. База данных (Flyway)

### V1__telegram_bindings.sql
```sql
CREATE TABLE telegram_bindings (
    user_id     uuid PRIMARY KEY,
    phone       varchar(20)  NOT NULL,
    telegram_id bigint       NOT NULL UNIQUE,
    username    varchar(64),
    linked_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    version     bigint       NOT NULL DEFAULT 0
);
CREATE INDEX idx_telegram_bindings_phone ON telegram_bindings (phone);
```

### V2__verification_requests.sql
```sql
CREATE TABLE verification_requests (
    id              uuid PRIMARY KEY,
    user_id         uuid        NOT NULL,
    operation_type  varchar(64) NOT NULL,
    operation_ref   varchar(128) NOT NULL,
    status          varchar(16) NOT NULL CHECK (status IN ('PENDING','SENT','APPROVED','REJECTED','EXPIRED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    completed_at    timestamptz
);
CREATE INDEX idx_verification_requests_user ON verification_requests (user_id, created_at DESC);
```

## 3. Redis-ключи

| Ключ | Тип | TTL | Назначение |
|---|---|---|---|
| `tg:otp:{verificationId}` | hash `{hash, userId, operationRef, attempts}` | 5 мин | Код 2FA (только SHA-256, как `OneTimeCodeStore`) |
| `tg:otp:used:{verificationId}` | string | 24 ч | Идемпотентность повторной проверки |
| `tg:otp:fail:{verificationId}` | counter | 5 мин | Блокировка после 5 неудач (как `OtpService`) |
| `tg:update:{updateId}` | string | 24 ч | Дедуп webhook-апдейтов Telegram |
| `tg:lock:bind:{userId}` | string (SET NX) | 30 с | Распределённая блокировка привязки |
| `tg:ratelimit:send` | counter | 1 с, лимит 25 | Защита лимита Telegram API 30 msg/с |

## 4. RabbitMQ — контракт событий

Общий exchange `domain.events` (topic), DLX `domain.events.dlx`. Все сообщения — JSON-`record` с `eventId` (UUID) и `occurredAt` (Instant), как требует §8 основного проекта.

### Очереди микросервиса

| Очередь | Routing key(s) | DLQ |
|---|---|---|
| `telegram.verification.q` | `verification.requested` | `telegram.verification.q.dlq` |
| `telegram.user-sync.q` | `user.registered`, `user.phone.changed`, `user.blocked`, `user.unblocked` | `telegram.user-sync.q.dlq` |

Retry: `spring.rabbitmq.listener.simple.retry` — 3 попытки (1s, 2s, 4s), затем DLX → DLQ.

### События, публикуемые микросервисом

| Routing key | Payload | Когда |
|---|---|---|
| `verification.sent` | `{eventId, occurredAt, verificationId}` | код отправлен в Telegram |
| `verification.completed` | `{eventId, occurredAt, verificationId, status}` | код проверен |
| `telegram.linked` | `{eventId, occurredAt, userId, telegramId, phone}` | привязка завершена |
| `telegram.unlinked` | `{eventId, occurredAt, userId}` | отвязка |

### События, потребляемые от основного бэкенда

`verification.requested`:
```json
{
  "eventId": "uuid",
  "occurredAt": "2026-...",
  "verificationId": "uuid",
  "userId": "uuid",
  "operationType": "LOAN_SIGNATURE",
  "operationRef": "application-uuid",
  "ttlMinutes": 5
}
```

`user.phone.changed`:
```json
{ "eventId": "uuid", "occurredAt": "...", "userId": "uuid", "oldPhone": "+375...", "newPhone": "+375..." }
```

## 5. HTTP API микросервиса

### Публичные (через nginx)

| Метод | Путь | Auth | Описание |
|---|---|---|---|
| POST | `/telegram/webhook` | none | Приём апдейтов Telegram |
| GET | `/actuator/health/**` | none | Health check |

### Для фронта (JWT основного бэкенда)

| Метод | Путь | Auth | Описание |
|---|---|---|---|
| GET | `/api/verify/{id}/status` | Bearer JWT | Статус отправки кода (`PENDING`/`SENT`/`APPROVED`) |
| POST | `/api/verify/{id}/confirm` | Bearer JWT | Проверка кода: тело `{ "code": "123456" }` |

Ответ confirm: `{ "verified": true }` при успехе, `400` при неверном/сгоревшем коде.

### Внутренние (service-token)

Вызываются основным бэкендом (заголовок `X-Service-Token`).

## 6. Поток привязки Telegram (первичная)

1. Пользователь отправляет боту `/start`.
2. Бот отвечает сообщением с `ReplyKeyboardMarkup` (`request_contact = true`): «Поделитесь номером телефона».
3. Пользователь нажимает кнопку → Telegram присылает `message.contact` с `phone_number`.
4. Бот валидирует: `contact.user_id == message.from.id` (подлинность контакта).
5. Бот вызывает основной бэкенд `GET /api/internal/users/by-phone?phone=...` (service-token) → получает `userId`.
6. Бот сохраняет `telegram_bindings (userId, phone, telegramId, username)`.
7. Бот публикует событие `telegram.linked`.
8. Бот отправляет пользователю: «Аккаунт привязан».

При смене номера в профиле основной бэкенд публикует `user.phone.changed` → микросервис обновляет `phone` в binding (и при несовпадении с Telegram — инвалидирует привязку, отправляет уведомление в Telegram).

## 7. Поток 2FA / подписи операций

См. архитектурный план (раздел 2). Ключевые моменты:

1. Основной бэкенд генерирует `verificationId` (UUID) синхронно, сохраняет состояние `PENDING`, публикует `verification.requested` через outbox.
2. Микросервис consumer генерирует 6-значный код, сохраняет хэш в Redis `tg:otp:{verificationId}` (TTL 5 мин), отправляет в Telegram, публикует `verification.sent`.
3. Фронт поллит `GET /api/verify/{id}/status` до статуса `SENT`, затем показывает форму ввода кода.
4. Пользователь вводит код → фронт отправляет `POST /api/verify/{id}/confirm` **напрямую в микросервис** (nginx проксирует `/api/verify/**`).
5. Микросервис проверяет код (constant-time compare, 3 попытки, burn). При успехе:
   - удаляет `tg:otp:{id}`, ставит `tg:otp:used:{id}`;
   - синхронно вызывает `POST /api/internal/verifications/{id}/complete` (service-token);
   - публикует `verification.completed`.
6. Основной бэкенд выполняет бизнес-операцию (идемпотентно по `verificationId`).

Код никогда не передаётся в основной бэкенд.

## 8. Масштабирование и высокие нагрузки

- **Telegram-бот в кластере:** только Webhook (не Long Polling). nginx балансирует между инстансами.
- **Дедуп апдейтов:** `tg:update:{updateId}` SET NX TTL 24 ч.
- **Распределённые блокировки:** Redis SET NX (`tg:lock:bind:{userId}`) + ShedLock для `@Scheduled`.
- **Rate limit Telegram API:** `tg:ratelimit:send` (25 msg/с). При превышении — сообщение остаётся в очереди RabbitMQ (не ack'ается).
- **Буферизация всплесков:** durable-очереди RabbitMQ с prefetch 20, concurrency 5–10.
- **Circuit breaker** для Telegram HTTP API: при серии ошибок — остановка отправки, сообщения остаются в очереди.

## 9. Переменные окружения

| Переменная | Обязательна | Описание |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | да | Токен бота от @BotFather |
| `TELEGRAM_BOT_USERNAME` | да | Username бота |
| `JWT_SECRET` | да | Общий с основным бэкендом (≥32 символа) |
| `SERVICE_TOKEN` | да | Для вызовов основного бэкенда |
| `POSTGRES_URL` | да | `jdbc:postgresql://.../nosurprise_notifications` |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | да | |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | да | |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` / `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | да | |
| `MAIN_BACKEND_URL` | да | `http://backend:8080` |
| `SERVER_PORT` | нет | По умолчанию 8090 |

## 10. Запуск

### Локально (без Docker)
```bash
cd notification-service
# требуется запущенный Postgres (БД nosurprise_notifications), Redis, RabbitMQ
gradle bootRun
```

### В составе docker-compose
```bash
docker compose up --build
```
Сервис поднимется на порту 8090 (внутри сети), снаружи доступен через nginx по путям `/api/verify/**` и `/telegram/webhook`.

### Проверка
```bash
curl http://localhost:8080/actuator/health   # основной бэкенд
# микросервис доступен внутри сети; снаружи health через nginx не проксирован по умолчанию
```

## 11. TODO (не реализовано в скелете)

- [ ] `Clock` bean подключён (сделано).
- [ ] Реальная привязка `userId` по phone (сейчас заглушка в `TelegramWebhookController` — нужен вызов основного бэкенда).
- [ ] Отправка кода в Telegram по `telegramId` (нужен lookup binding по `userId`).
- [ ] `IdempotencyGuard` (скопировать из основного бэкенда или вынести в общую библиотеку).
- [ ] `OutboxPublisher` для микросервиса (если потребуется гарантированная публикация).
- [ ] Тесты (unit + integration с Testcontainers).
- [ ] ShedLock-конфигурация.
- [ ] Удаление старой phone verification в основном бэкенде и фронте (см. архитектурный план, раздел 4).
- [ ] Добавление событий `user.registered/phone.changed/blocked` в основной бэкенд.