# HANDOFF.md — передача состояния

## Общее

- Ветка: `backend_java25` (база — `dev`, коммит `eeaa5a3`).
- Последние коммиты: `1bb570b` — расчётное ядро; `263802b` — Money.MC; `cb3bca2` — REST API + MongoDB; `a505420b` — Redis-кэш; `4a3b93b` — rate limit; `681f408` — user module + V2; `9b9a74f` — регистрация + verify-email; `fb4448c` — RabbitMQ mail pipeline; `4a41e5f` — TokenService (HS256 JWT); `4d20205` — RefreshTokenService (Redis); `1a6f46a` — login/refresh/logout; `f01caff` — SecurityConfig JWT resource server; `62311ca` — GET /api/me.
- Статус: коммиты 1–3 (расчётное ядро, REST API, Redis-кэш) + rate limit + users + регистрация/подтверждение email + mail-пайплайн + **локальный логин, JWT, refresh, logout, GET /api/me и защита роутов** завершены. `POST /api/calc/stress` не начат.

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

### Коммит `4a41e5f` — TokenService: HS256 access JWT (§7)

- `auth.service.TokenService`: генерация HS256 JWT (`com.nimbusds`), subject = userId (UUID-строка), `iat`/`exp` = now + TTL 15 мин; `validate(token)` — подпись + срок → userId, иначе `InvalidAccessTokenException`. Также `sha256Hex(String)` — SHA-256 hex (для Redis-ключей refresh). Access-токен нигде не хранится (ни PostgreSQL, ни Redis) — только в ответе API.
- `auth.service.JwtProperties` (`@ConfigurationProperties("app.jwt")`): `secret`/`accessTtl`/`refreshTtl`; компактный конструктор **fail-fast**: приложение не стартует, если секрет пустой или короче 32 символов (256 бит для HS256) или TTL неположительный. `application.yml`: dev-дефолт секрета **удалён** — `app.jwt.secret: ${JWT_SECRET:}`; регистрация `JwtPropertiesConfig` (`config`). В тестах секрет задается через свойства/параметры конструктора.
- `common.ClockConfig`: бин `Clock.systemUTC()` (время UTC §9), подставляется в тестах фиксированным.
- Тесты: `TokenServiceTest` 6 (round-trip, HS256+exp, чужая подпись, мусор, истёкший, sha256), `JwtPropertiesTest` 6 (fail-fast).

### Коммит `4d20205` — RefreshTokenService: opaque refresh в Redis (§6.3/§7)

- `auth.service.RefreshTokenStore` (StringRedisTemplate): ключ **`refresh:{userId}:{sha256(token)}`** → метаданные (строка `createdAt|issuedIp`, JSON не использован — без сериализационных зависимостей), **TTL 30 дн**. Сам opaque-токен нигде не хранится — только SHA-256 в имени ключа; повреждённое значение читается как `Optional.empty`.
- `auth.service.RefreshTokenService`: токен 32 байта base64url (43 симв., **не JWT**); `issue(userId, ip)`; `rotate(cookieValue, ip)` — read → **delete старого** → issue нового (ротация при каждом refresh); `revoke(cookieValue)` — logout, идемпотентно. Cookie-значение = **`{userId}.{opaqueToken}`** (userId нужен для ключа Redis — opaque-токен сам пользователя не определяет). `InvalidRefreshTokenException` для мусора/неизвестного токена, одинаковая для всех причин.
- `IssuedRefreshToken` (userId, token, cookieValue, expiresAt), `RefreshTokenMetadata` (createdAt, issuedIp).
- Тесты: `RefreshTokenStoreTest` 6 (формат ключа+TTL, токен не в значении, read/delete, corrupted), `RefreshTokenServiceTest` 5 (opaque≠JWT, ротация, старый невалиден, мусор в cookie, revoke).

### Коммит `1a6f46a` — логин / refresh / logout + ProblemDetail-коды (§7/§9)

- `auth.service.AuthService`: `login(email, password, ip)` — email trim+lowercase; **неизвестный email и неверный пароль → одна и та же `InvalidCredentialsException`** (существование email не раскрывается; OAuth-пользователь без password_hash — тоже она); пароль проверяется первым, затем `email_verified=false` → `EmailNotVerifiedException` (403). `refresh(cookieValue, ip)` — ротация через RefreshTokenService; `logout(cookieValue)` — отзыв, без cookie идемпотентен. Пароли — реальный `DelegatingPasswordEncoder` (bcrypt), в unit-тестах тоже.
- `AuthController` (+): `POST /api/auth/login` → 200 `{accessToken, tokenType:"Bearer", expiresIn:900}` + `Set-Cookie: refresh_token=...`; `POST /api/auth/refresh` → 200 + новый Set-Cookie (ротация); `POST /api/auth/logout` → 204 + cookie с `Max-Age=0`. Cookie (§7): HttpOnly, **SameSite=Lax, Path=/api/auth**, `Secure` только при `request.isSecure()`. **POST /api/auth/logout-all НЕ добавлен** (нет в контракте). IP клиента — `request.getRemoteAddr()` (forward-headers, как в rate limit).
- `ErrorCode` + `INVALID_CREDENTIALS` (401), `EMAIL_NOT_VERIFIED` (403), `INVALID_REFRESH_TOKEN` (401), `UNAUTHORIZED`, `FORBIDDEN`; `GlobalExceptionHandler` (+3 маппинга).
- Тесты: `AuthServiceTest` 10 (успешный логин, нормализация email, неизвестный email, неверный пароль, **одинаковая ошибка** для обоих, неподтверждённый email, OAuth-без-пароля, refresh-ротация, refresh без cookie, logout), `AuthControllerTest` 7→17 (+login/refresh/logout, cookie-флаги, Secure при https, коды ошибок).

### Коммит `f01caff` — SecurityConfig: JWT resource server (§7)

- Stateless (`SessionCreationPolicy.STATELESS`), CSRF off (как раньше); `oauth2ResourceServer().jwt()` — Nimbus, `JwtDecoder` = HS256 `SecretKeySpec` из `JwtProperties.secret` (тот же секрет, что TokenService). Ошибки 401/403 — **ProblemDetail (RFC 7807) с code**, пишутся напрямую (константные строки, без ObjectMapper — Boot 4 автоконфигурирует Jackson 3, см. открытый вопрос №8): `ProblemEntryPoint` (401 `UNAUTHORIZED`), `ProblemAccessDeniedHandler` (403 `FORBIDDEN`), а также как **failure-handler ресурсного сервера** (невалидный JWT иначе отдавал 401 с пустым телом).
- Публичные: `/api/calc/**`, `/api/offers`, `/api/auth/**`, `/actuator/health/**`; `/api/admin/**` — `hasRole("ADMIN")`; остальное — authenticated, **включая `/actuator/prometheus`** (решение владельца: ранее был открытым, закрыт по требованию §7).
- Тесты: `SecurityConfigTest` 6 через `WebApplicationContextRunner` (реальная SecurityFilterChain, JWT через постпроцессор spring-security-test; «прошёл security» = 404, контроллеров в срезе нет): 401 без JWT + code, 403 не-ADMIN на `/api/admin/**` + code, 404 для ADMIN, валидный JWT открывает защищённый роут, публичные роуты, prometheus 401.

### Коммит `62311ca` — GET /api/me (§9)

- `user.web.UserController` (`GET /api/me`, только authenticated): subject JWT → userId → `UserService.findById` → `MeResponse{id, email, emailVerified, roles[]}`. **password_hash / provider / provider_id наружу не идут**. Пользователя с валидным токеном нет (удалён) → `user.UserNotFoundException` → 401 `UNAUTHORIZED` (маппинг в `GlobalExceptionHandler`).
- Тесты: `UserControllerTest` 5 — @WebMvcTest с **реальной** security-цепочкой (`@Import(SecurityConfig, JwtPropertiesConfig)`, фильтры включены, JWT генерирует TokenService тем же секретом): валидный JWT → все поля + отсутствие внутренних; без JWT → 401; подделанный JWT → 401; subject без пользователя → 401; роль USER проходит (требуется лишь authenticated).

### OAuth Google/GitHub + первый ADMIN + roles claim в JWT

- Профиль `oauth`: `application-oauth.yml` использует env `GOOGLE_CLIENT_ID/SECRET`, `GITHUB_CLIENT_ID/SECRET`; без них контекст не стартует.
- `UserService.provisionOAuthUser`: поиск по email, создание/связывание; `password_hash` не перезаписывается; provider не перезаписывается, если уже привязан к другому OAuth-провайдеру; `email_verified` устанавливается при подтверждении провайдером или если уже verified.
- `FirstAdminBootstrap` (`ApplicationRunner`): создаёт ADMIN из `APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD`; при существующем пользователе добавляет роль ADMIN без перезаписи пароля/provider/ролей; пустые env — безопасный пропуск.
- `OAuth2AuthenticationSuccessHandler`: provisioning, выдача access JWT с claim `roles`, refresh token в Redis (`refresh:{userId}:{sha256(token)}`, TTL 30 дн), cookie `refresh_token` (HttpOnly, SameSite=Lax, Path=/api/auth, Secure при HTTPS).
- `SecurityConfig`: `oauth2Login` только в профиле `oauth`; `JwtAuthenticationConverter` маппит claim `roles` → `ROLE_*`; `/api/admin/**` требует `ROLE_ADMIN`.
- `TokenService.generate(...)` поддерживает roles; `AuthService` передаёт роли при login/refresh.
- `/api/me` по-прежнему возвращает `id, email, emailVerified, roles` без префикса `ROLE_`.

### Банковские предложения и сохранённые сценарии

- Миграции `V3__bank_offers.sql`, `V4__saved_scenarios.sql`, `V5__seed_offers.sql`; `V1`/`V2` не изменены.
- `offer`: `BankOffer`, `BankOfferRepository`, `OfferService`, `OfferCache` (`offers:all`, TTL 5 мин, отказоустойчивый Redis), контроллеры `/api/offers` и `/api/admin/offers`, DTO с деньгами-строками.
- `PUT /api/admin/offers/{id}` при фактическом изменении ставки публикует `OfferRateChanged` через `@TransactionalEventListener(AFTER_COMMIT)` → routing key `offer.rate.changed`.
- `scenario`: `Scenario`, `ScenarioRepository`, `ScenarioService`, контроллер `/api/scenarios` и `/api/scenarios/compare?ids=...` (2–3 id), DTO; создание сценария выполняет расчёт через `CalculatorService`, сохраняет расчёт в Mongo и ссылается на `calcId`; проверка активного `offerId`.
- Права: сценарии только владельца; чужой сценарий → 404; compare с 1/4 id → 400 `INVALID_REQUEST`; неактивный/несуществующий offer → 400 `INVALID_OFFER`.

### Уведомления об изменении ставки банковского предложения

- Модуль `notification`: consumer `OfferRateChangedConsumer` слушает `notification.offer-rate-changed.q` (exchange `domain.events`, routing key `offer.rate.changed`).
- Очередь и DLQ объявлены в `RabbitNotificationConfig`; retry/DLQ используются из существующей конфигурации RabbitMQ.
- Идемпотентность по `eventId` в Redis: ключ `notification:offer-rate-changed:processed:{eventId}`, TTL 7 дней; ключ ставится только после успешной публикации всех `mail.requested`.
- Письма отправляются через существующий mail pipeline всем активным пользователям с подтверждённым email и непустым email.
- Текст письма содержит bankName, oldRate и newRate в читаемом виде; внутренние ID не включаются.
- Если offer не найден, событие логируется и отмечается обработанным; невалидное событие выбрасывает исключение для retry/DLQ.

### Асинхронный PDF-экспорт сценария

- Миграция `V6__notifications.sql` создаёт таблицу `notifications`.
- `notification`: `Notification`, `NotificationRepository`, `NotificationService` с идемпотентным созданием `PDF_READY`.
- `pdf`: `PdfService` (OpenPDF, встроенный шрифт Arial.ttf), `ExportPdfRequested`, publisher `AFTER_COMMIT`, очередь `export.pdf.q` + DLQ, consumer с GridFS и Redis-идемпотентностью `pdf:export:processed:{eventId}` (TTL 7 дней).
- Endpoints: `POST /api/scenarios/{id}/export/pdf` → 202; `GET /api/scenarios/{id}/pdf` → PDF с корректными заголовками.
- Доступ только владельцу сценария; чужой/несуществующий → 404 `SCENARIO_NOT_FOUND`; PDF не готов → 404 `PDF_NOT_READY`; метаданные есть, файла нет → 404 `PDF_NOT_FOUND`.
- PDF содержит заголовок, дату, параметры, результаты, график и данные сценария/offer; деньги в читаемом формате.

## Проверки

- `gradlew test --rerun-tasks` — BUILD SUCCESSFUL, **150 тестов, 0 падений** (96 прежних + 54 новых):
  - JWT: `TokenServiceTest` 6, `JwtPropertiesTest` 6;
  - refresh: `RefreshTokenStoreTest` 6, `RefreshTokenServiceTest` 5;
  - логин: `AuthServiceTest` 10, `AuthControllerTest` 17 (+7 новых);
  - security: `SecurityConfigTest` 6;
  - /api/me: `UserControllerTest` 5.
- Testcontainers по-прежнему не используются (решение зафиксировано).
- Чистота calculator: не тронут. Контракт POST/GET /api/calc, DTO, кэш, rate limit, mail-пайплайн — не изменялись.
- `gradlew bootJar` — BUILD SUCCESSFUL (до этого этапа; после — не перезапускался).

## Ограничения и дисциплина

- Реализация коммитов 1–2 НЕ считается независимо проверенной — ревью кода не завершено. Коммиты rate limit / user / auth / mail / JWT-этапа — тем более.
- `frontend/nginx.conf` (`~ ^/(api|oauth2|login/oauth2)/`) и vite-прокси `/api` уже покрывают `/api/auth/**` и `/api/me` — изменений не потребовалось.
- Границы соблюдены: НЕ реализованы OAuth Google/GitHub, offers, scenarios, notifications (в БД), PDF, первый ADMIN. **logout-all не добавлен** (нет в API-контракте). Testcontainers не добавлены. `calculator/`, контракт /api/calc, rate limit, mail-пайплайн не менялись.
- Rate limit: с появлением JWT-аутентификации ветка 120/мин по userId заработала автоматически (ключ `ratelimit:calc:{userId}`); изменений кода не потребовалось.
- JWT_SECRET: dev-дефолт из application.yml удалён, `JwtProperties` fail-fast (пустой/короткий секрет — приложение не стартует). Локальный запуск требует `JWT_SECRET` (минимум 32 символа) в env.
- Access JWT хранится только у клиента (§7, в памяти фронта); refresh — только SHA-256 в Redis.
- Claim-идемпотентность consumer — Redis SET NX до отправки; возможен окно гонки «claim взят, процесс умер» → повтор будет пропущен, письмо потеряется до исчерпания TTL 72 ч (приемлемо для этапа; альтернатива — outbox).
- Тест AFTER_COMMIT использует stub `PlatformTransactionManager` (реальный Spring-механизм синхронизаций, без БД); интеграционный тест с настоящей PG-транзакцией — кандидат на Testcontainers позже.

## Открытые вопросы / требуют решения

1. **Хэш canonical-строки.** Формат канонизации `ParamsHasher` (поля через `|`, порядок досрочных по компаратору type/month/startMonth/...) зафиксирован кодом; если когда-нибудь поменяете канонизацию, старые документы в Mongo получат «чужие» paramsHash — менять только с миграцией. Менять канонизацию нельзя и из-за Redis-ключей: после TTL 1 ч ключи перезапишутся, но в пределах часа возможны «чужие» попадания кэша.
2. **`CalculationDocument` переиспользует records calculator** (`LoanParams`, `LoanSummary`, `ScheduleRow`, `Savings`). Компактный конструктор `LoanParams` валидирует при десериализации — валидно для сохранённых значений, но связь модель ↔ документ стоит иметь в виду при рефакторинге ядра.
3. **Redis hit быстрее Mongo-only.** Прогрев при Mongo hit делает кэш рабочим и при Redis-first порядке; в пределах TTL Redis закрывает и Mongo-lookup, и пересчёт. Обратная ситуация (Redis hit, документа в Mongo нет) обработана evict + пересчёт.
4. **Ответ register скрывает существование email** (требование «не раскрывай»): 202 generic даже для занятого адреса. Если когда-нибудь понадобится 409 EMAIL_TAKEN — это осознанное раскрытие, решение за владельцем контракта.
5. **`mail:processed:{eventId}`** — расширение таблицы ключей §6.3 (хранилище идемпотентности consumer). TTL 72 ч — при повторной доставке позже 72 ч письмо уйдёт повторно; для verification-писем ок, для критичных дубль-защит нужен долговременный стор.
6. **Письмо-подтверждение отправляется в plain text** (SimpleMailMessage). MIME/HTML-шаблоны и локализация — отдельная задача.
7. **Закрыто (коммиты OAuth/admin):** первый ADMIN создаётся при старте из `APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD` (`FirstAdminBootstrap`), роли проброшены в JWT через claim `roles` + `JwtAuthenticationConverter`; `/api/admin/**` доступен ADMIN и запрещён USER.
8. **Закрыто (коммит `fix(ratelimit)`):** скрытая проблема с Jackson в `RateLimitConfig`/`RateLimitFilter` устранена — зависимость от `com.fasterxml.jackson.databind.ObjectMapper` убрана, JSON 429-ответа собирается вручную (как в `SecurityConfig`), добавлен тест минимального Spring-контекста `RateLimitConfigContextTest`. Контекст стартует без Jackson-2 ObjectMapper-бина.
9. **Cookie `{userId}.{opaqueToken}`** — userId в cookie открытым текстом (это не секрет: subject JWT и так виден). Если захотите скрыть — понадобится lookup-ключ (например, индекс `refresh:cookie:{sha256(cookie)} → userId`), решение за владельцем контракта.
10. **Метаданные refresh — строка `createdAt|issuedIp`** (без JSON): компактно, но расширение формата (userAgent и т.п.) потребует миграции живых ключей; TTL 30 дн сам «мигрирует» записи за месяц.

## Следующий этап

По дорожной карте: `POST /api/calc/stress` (остаток коммита 3), **userId в расчётах** (теперь есть аутентификация: анонимные расчёты с TTL 7 дней, пользовательские — бессрочно, `CalculationDocument.userId` уже в схеме), первый ADMIN (`APP_ADMIN_EMAIL`/`APP_ADMIN_PASSWORD`, roles claim в JWT). Затем offers/scenarios/notifications/PDF. Начинать только после подтверждения.
