# HANDOFF.md — передача состояния

## Общее

- Ветка: `backend_java25` (база — `dev`, коммит `eeaa5a3`).
- Последние коммиты: `1bb570b` — расчётное ядро; `263802b` — Money.MC; `cb3bca2` — REST API + MongoDB; `a505420b` — Redis-кэш; `4a3b93b` — rate limit; `681f408` — user module + V2; `9b9a74f` — регистрация + verify-email; `fb4448c` — RabbitMQ mail pipeline.
- Статус: коммиты 1–3 (расчётное ядро, REST API, Redis-кэш) + rate limit + users + регистрация/подтверждение email + mail-пайплайн завершены. `POST /api/calc/stress` не начат.

## Выполненная работа

### Коммит 1 — чистое расчётное ядро (`dev.nosurprise.calculator`)

Без Spring, БД и IO; зависимость только от `common.Money`:

- Модель: `PaymentType`, `EarlyRepaymentType`, `EarlyRepaymentMode`, `EarlyRepayment` (ONE_TIME / REGULAR), `LoanParams` (валидация §5), `ScheduleRow`, `LoanSummary`, `Savings`, `CalculationResult`.
- `MortgageCalculator`: аннуитет/дифференцированный; проценты месяца округляются до копеек HALF_UP и уже округлённые участвуют в остатке; последняя строка гасит остаток ровно до `0.00`; досрочные применяются после планового платежа и ограничены остатком; `REDUCE_PAYMENT` пересчитывает платёж/основной долг; `Savings` — сравнение с базой без досрочных, `null` если досрочных нет.
- Эталоны §5 покрыты (`MortgageCalculatorTest`), сверялись с правилами §5 и банковским калькулятором.

### Коммит `263802b` — деления на Money.MC

Все промежуточные деления `MortgageCalculator` переведены с `scale(12)+HALF_UP` на `Money.MC` (20 цифр, HALF_EVEN) — открытый вопрос №1 прежней версии HANDOFF закрыт в пользу Money.MC, INSTRUCTION.md §5 соответствует коду. Округление денег наружу — прежнее `Money.round` (scale 2, HALF_UP). Формулы, порядок досрочных, closing-логика и структура графика не менялись; существующие ожидания тестов не правились (все эталоны зелёные, `gradlew test --rerun-tasks` BUILD SUCCESSFUL).

### Коммит 2 — REST API + MongoDB (`POST /api/calc`, `GET /api/calc/{calcId}`)

Модуль `dev.nosurprise.calc` (правило зависимостей `calc → calculator` внесено в INSTRUCTION.md §4):

- **web** (`calc.web`): `CalculatorController` (`POST /api/calc`, `GET /api/calc/{calcId}`), DTO-records (`CalcRequest` + jakarta-валидация, `EarlyRepaymentRequest`, `CalcResponse`, `ScheduleRowResponse`, `SavingsResponse` — все деньги в JSON строки), `CalcRequestMapper` (DTO → `LoanParams`, кросс-поля ONE_TIME/REGULAR → `InvalidCalculationException`), `CalcResponseMapper` (все деньги через `toPlainString`, `loanAmount` — `Money.round`).
- **service** (`calc.service`): `CalculatorService` — paramsHash → поиск в Mongo по `paramsHash` → при находке возвращает существующий `calcId` без новой записи; иначе `MortgageCalculator.calculate` + сохранение. `ParamsHasher` — чистый Java: канонический вид (`stripTrailingZeros().toPlainString()`, досрочные отсортированы), sha256 hex (64 символа). `CalcNotFoundException` — 404.
- **repository** (`calc.repository`): `CalculationDocument` (`@Document("calculations")`: `paramsHash`, `userId` (пока всегда null), `params`/`summary`/`savings`/`schedule` (переиспользуют records calculator), `createdAt`, `expireAt`), `CalculationRepository` (`findByParamsHash`). Аннотации `@Indexed`/`@CompoundIndex` не используются.
- **config** (`calc.config.CalculationIndexes`): индексы создаются явно через `MongoTemplate.indexOps(...)` + `IndexOperations.createIndex`: `paramsHash` asc; `userId` asc + `createdAt` desc; `expireAt` asc TTL (`expireAfterSeconds = 0`, удаляет только документы с `expireAt`).
- **anonymous TTL**: пользовательской аутентификации нет — все расчёты анонимные, `expireAt = now + 7 дней`.
- **common.problem**: `ErrorCode` (`VALIDATION_FAILED`, `INVALID_REQUEST_BODY`, `INVALID_CALC_PARAMS`, `CALC_NOT_FOUND`, `INTERNAL_ERROR`), `GlobalExceptionHandler` (`@RestControllerAdvice`, RFC 7807 ProblemDetail + поле `code`). `IllegalArgumentException` глобально НЕ мапится → 500 `INTERNAL_ERROR`; правила расчёта бросают `calculator.InvalidCalculationException extends IllegalArgumentException` (тесты коммита 1 остались зелёными).
- **конфиг**: `application.yml` + `spring.data.mongodb.representation.big-decimal=decimal128` (деньги в Mongo — Decimal128). Больше конфиг и `build.gradle` не менялись; `SecurityConfig`, миграции, Docker, frontend не тронуты.

### Redis-кэш calc:{paramsHash} → calcId, TTL 1 ч (`calc.cache`)

- **Порт** (`calc.cache.CalcCache`): интерфейс без Spring — `findCalcId(paramsHash)`, `save(paramsHash, calcId)`, `evict(paramsHash)`. Формат ключа и TTL — забота реализации.
- **Реализация** (`calc.cache.RedisCalcCache`): `StringRedisTemplate`, ключ `calc:{paramsHash}` → calcId, TTL константа `Duration.ofHours(1)` (§6.3). `@Component`, StringRedisTemplate приходит из автоконфигурации (`spring-boot-starter-data-redis` и `spring.data.redis` в `application.yml` были уже — build.gradle и yml не менялись).
- **Порядок операций в `CalculatorService.calculate`** (согласован с ревью, Redis-first):
  1. DTO → `LoanParams` → `paramsHash`;
  2. Redis `findCalcId` → hit: документ по calcId из Mongo → ответ без пересчёта (Mongo по paramsHash не запрашивается);
  3. Redis hit, но документа нет (устаревшая запись) → `evict` → дальше как cache miss;
  4. Redis miss → Mongo `findByParamsHash` → hit: ответ + прогрев кэша `save(hash, calcId)`;
  5. полный miss → `MortgageCalculator.calculate` → сохранение в Mongo (`expireAt = now + 7 дней`) → после успешного `save` запись в кэш.
- **Отказоустойчивость**: все операции кэша (findCalcId/save/evict) обёрнуты try/catch с `log.warn` — недоступный Redis трактуется как cache miss и не ломает POST /api/calc.
- **Контракт не менялся**: `POST /api/calc` и `GET /api/calc/{calcId}`, DTO, мапперы, контроллер, коды ошибок — прежние. GET в кэше не участвует.

### Коммит `4a3b93b` — rate limit POST /api/calc (`common.ratelimit`, §6.3)

- `RateLimitFilter` (`OncePerRequestFilter`): только `POST /api/calc` (точное совпадение URI). Principal: `Authentication` из SecurityContext (не anonymous) → ключ `ratelimit:calc:{userId}` и лимит 120/мин; иначе `request.getRemoteAddr()` → ключ `ratelimit:calc:{ip}` и лимит 30/мин. IP — из `getRemoteAddr()`: при `forward-headers-strategy: framework` Spring уже подставляет клиентский IP из `X-Forwarded-For` доверенного прокси.
- `RateLimiter` (Redis): INCR ключа; `count == 1` → EXPIRE 60 с (окно); `count > limit` → отказ, `Retry-After` = остаток TTL ключа (fallback — 60 с, если TTL недоступен).
- Превышение: HTTP 429 + заголовок `Retry-After` + ProblemDetail `code=RATE_LIMITED` (`application/problem+json`, пишется фильтром вручную).
- Сбой Redis → warning, запрос пропускается (не ломает /api/calc).
- Лимиты в `application.yml` (`app.ratelimit.calc.anonymous-per-minute/authenticated-per-minute/window`). `RateLimitConfig`: FilterRegistrationBean с order −90 (после SecurityFilterChain (−100), чтобы видеть SecurityContext будущего JWT-фильтра).
- Пользовательская ветка (120/мин) полноценно заработает с появлением аутентификации; ключ и лимит заложены.

### Коммит `681f408` — users и миграция V2 (`user`, §6.1)

- `V2__users_and_roles.sql`: `users` (id uuid PK, email unique, password_hash nullable, email_verified, provider varchar(16) + CHECK LOCAL/GOOGLE/GITHUB, provider_id, created_at timestamptz) и `user_roles` (user_id FK ON DELETE CASCADE, role varchar(16) + CHECK USER/ADMIN, PK(user_id, role)). **Таблицы refresh_tokens нет** — refresh-токены только в Redis; `V1__init.sql` не изменён.
- **id = UUID**: генерируется приложением (`@GeneratedValue(UUID)`), неугадываем, кладётся строкой в JWT-subject и события; **provider = varchar + CHECK** (не PG enum): расширяется миграцией констрейнта, мапится `@Enumerated(STRING)` без кастомных типов.
- Модуль `user`: `UserEntity` (roles — `@ElementCollection` → user_roles), `Role`, `Provider`, `UserRepository`, публичный `UserService` (`createLocalUser` — email_verified=false, роль USER; `findByEmail`, `findById`, `markEmailVerified` — идемпотентно). ddl-auto: validate.
- `SecurityConfig`: добавлен `PasswordEncoder` = `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (bcrypt).

### Коммит `9b9a74f` — регистрация + подтверждение email (`auth`, §7/§9)

- **POST /api/auth/register** (email + password, jakarta-валидация: email, password 8..72 — лимит bcrypt): `RegisterService` нормализует email (trim+lowercase); существующий email **молча возвращает тот же ответ** — 202 с generic-сообщением, ничего не создаёт и не публикует (существование email не раскрывается). Новый: bcrypt-хэш → user (email_verified=false) → `verify:{token}` в Redis (`VerificationTokenStore`, токен 32 байта base64url, TTL 24 ч) → in-app событие `VerificationEmailRequested` внутри транзакции.
- **GET /api/auth/verify-email?token=**: `VerificationService` — read токена → подтверждение пользователя → delete токена (токен удаляется только после успешного подтверждения — повторное подтверждение идемпотентно, email уже verified → 200). Токена нет/истёк/пользователь неизвестен → 400 `VERIFY_TOKEN_INVALID` (пользователей не раскрываем). Коды: `ErrorCode.VERIFY_TOKEN_INVALID`; `MissingServletRequestParameterException` → 400 `VALIDATION_FAILED`.
- Модули: `auth.web` (`AuthController`, DTO-records), `auth.service` (`RegisterService`, `VerificationService`, `VerificationTokenStore`, `VerifyTokenInvalidException`), `auth.event` (`VerificationEmailRequested`). Зависимость auth → user — по правилам §4.

### Коммит `fb4448c` — mail-пайплайн RabbitMQ (§8)

- Топология (`config.RabbitMailConfig`): topic-exchange `domain.events`, DLX `domain.events.dlx`; очередь `mail.send.q` (durable, `x-dead-letter-exchange=domain.events.dlx`, `x-dead-letter-routing-key=mail.requested`); `mail.send.q.dlq`; bindings по `mail.requested`. MessageConverter — `JacksonJsonMessageConverter` (Jackson 3).
- Retry: `spring.rabbitmq.listener.simple.retry` — enabled, 3 попытки, 1с→2с→4с; `default-requeue-rejected: false` → исчерпание retry → reject → DLX → `mail.send.q.dlq`.
- Публикация **после коммита**: `RegisterService` публикует in-app событие, `VerificationEmailMailBridge` слушает `@TransactionalEventListener(phase = AFTER_COMMIT)` и публикует `mail.requested` (`common.messaging.MailRequested` — JSON record: eventId UUID, occurredAt Instant, to/subject/body; письмо со ссылкой `{APP_BASE_URL}/api/auth/verify-email?token=`).
- Consumer (`notification.MailSendConsumer`, `@RabbitListener("mail.send.q")`): JavaMailSender (Mailpit localhost:1025 локально); **идемпотентность по eventId** — Redis SET NX `mail:processed:{eventId}` TTL 72 ч до отправки; при сбое отправки claim снимается и исключение прокидывается (retry/DLQ), повторная доставка снова пробует отправить.
- Конфиг: `MAIL_HOST`/`MAIL_PORT` (дефолты localhost:1025 = Mailpit), `MAIL_USERNAME`/`MAIL_PASSWORD` (без дефолтов в yml, секреты только env), `APP_MAIL_FROM`. `.env` в `.gitignore`.

## Проверки

- `gradlew test --rerun-tasks` — BUILD SUCCESSFUL, 96 тестов, 0 падений: 51 прежних + 45 новых:
  - rate limit: `RateLimiterTest` 5, `RateLimitFilterTest` 6;
  - user: `UserServiceTest` 3;
  - auth: `RegisterServiceTest` 3, `VerificationServiceTest` 5, `VerificationTokenStoreTest` 4, `RegistrationAfterCommitTest` 2 (событие AFTER_COMMIT: доставляется после commit, не доставляется при откате — через ApplicationContextRunner со stub PlatformTransactionManager), `AuthControllerTest` (@WebMvcTest) 7;
  - mail: `VerificationEmailMailBridgeTest` 2, `MailSendConsumerTest` 4, `RabbitMailConfigTest` 3 (топология DLX/DLQ без брокера + retry-свойства из настоящего application.yml через Binder);
  - `GlobalExceptionHandlerTest` +1 (VERIFY_TOKEN_INVALID).
- Testcontainers по-прежнему не используются (решение зафиксировано).
- Чистота calculator: не тронут. Контракт POST/GET /api/calc, DTO, кэш — не изменялись.
- `gradlew bootJar` — BUILD SUCCESSFUL.

## Ограничения и дисциплина

- Реализация коммитов 1–2 НЕ считается независимо проверенной — ревью кода не завершено. Коммиты rate limit / user / auth / mail — тем более.
- `frontend/nginx.conf` (`~ ^/(api|oauth2|login/oauth2)/`) и vite-прокси `/api` уже покрывают `/api/auth/**` — изменений не потребовалось.
- Границы соблюдены: НЕ реализованы логин, JWT, refresh, logout, `/api/me`, защита роутов, OAuth; offers, scenarios, notifications (в БД), PDF. Testcontainers не добавлены. `calculator/` и контракт /api/calc не менялись.
- Rate limit: пока нет аутентификации, все запросы лимитируются по IP (30/мин); ветка 120/мин активируется автоматически с появлением Authentication.
- Claim-идемпотентность consumer — Redis SET NX до отправки; возможен окно гонки «claim взят, процесс умер» → повтор будет пропущен, письмо потеряется до исчерпания TTL 72 ч (приемлемо для этапа; альтернатива — outbox).
- Тест AFTER_COMMIT использует stub `PlatformTransactionManager` (реальный Spring-механизм синхронизаций, без БД); интеграционный тест с настоящей PG-транзакцией — кандидат на Testcontainers позже.

## Открытые вопросы / требуют решения

1. **Хэш canonical-строки.** Формат канонизации `ParamsHasher` (поля через `|`, порядок досрочных по компаратору type/month/startMonth/...) зафиксирован кодом; если когда-нибудь поменяете канонизацию, старые документы в Mongo получат «чужие» paramsHash — менять только с миграцией. Менять канонизацию нельзя и из-за Redis-ключей: после TTL 1 ч ключи перезапишутся, но в пределах часа возможны «чужие» попадания кэша.
2. **`CalculationDocument` переиспользует records calculator** (`LoanParams`, `LoanSummary`, `ScheduleRow`, `Savings`). Компактный конструктор `LoanParams` валидирует при десериализации — валидно для сохранённых значений, но связь модель ↔ документ стоит иметь в виду при рефакторинге ядра.
3. **Redis hit быстрее Mongo-only.** Прогрев при Mongo hit делает кэш рабочим и при Redis-first порядке; в пределах TTL Redis закрывает и Mongo-lookup, и пересчёт. Обратная ситуация (Redis hit, документа в Mongo нет) обработана evict + пересчёт.
4. **Ответ register скрывает существование email** (требование «не раскрывай»): 202 generic даже для занятого адреса. Если когда-нибудь понадобится 409 EMAIL_TAKEN — это осознанное раскрытие, решение за владельцем контракта.
5. **`mail:processed:{eventId}`** — расширение таблицы ключей §6.3 (хранилище идемпотентности consumer). TTL 72 ч — при повторной доставке позже 72 ч письмо уйдёт повторно; для verification-писем ок, для критичных дубль-защит нужен долговременный стор.
6. **Письмо-подтверждение отправляется в plain text** (SimpleMailMessage). MIME/HTML-шаблоны и локализация — отдельная задача.
7. Первый ADMIN (`APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD` при старте, §6.1) ещё не реализован — сейчас в БД нет ни одного админа; понадобится до реализации offers-админки.

## Следующий этап

`POST /api/calc/stress` (остаток коммита 3). Rate limit реализован. Далее по дорожной карте: логин/JWT/refresh/logout, `userId` в расчётах, первый ADMIN. Начинать только после подтверждения.
