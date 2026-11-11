# HANDOFF.md — передача состояния

## Общее

- Ветка: `backend_java25` (база — `dev`, коммит `eeaa5a3`).
- Последние коммиты: `1bb570b` — расчётное ядро; `263802b` — перевод промежуточных делений на `Money.MC`; `cb3bca2` — REST API + MongoDB; далее коммит Redis-кэша (см. ниже).
- Статус: коммиты 1–2 из 5 завершены. Коммит 3 выполнен частично: **Redis-кэш calc:{paramsHash} реализован**; `POST /api/calc/stress` и rate limit **не начаты**.

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
- **Не тронуто**: `calculator/*`, `common/*`, `build.gradle`, `application.yml`; rate limit, RabbitMQ, auth, offers, scenarios, notifications, PDF не добавлены. Testcontainers не используются.

## Проверки

- `gradlew test --rerun-tasks` — BUILD SUCCESSFUL, 51 тест, 0 падений: 41 прежних + 10 новых: `RedisCalcCacheTest` 4 (мок `StringRedisTemplate`: ключ `calc:{hash}`, TTL 1 ч, evict, отсутствие ключа), `CalculatorServiceTest` 7→13 (redis hit без пересчёта; устаревшая запись → evict/пересчёт/запись; cache miss → расчёт+сохранение+запись в InOrder; Mongo hit с прогревом кэша; сбой Redis на чтении и на записи не ломают расчёт).
- Тестов Testcontainers/@DataMongoTest по-прежнему нет — кэш тестируется через интерфейс `CalcCache` и моки (решение зафиксировано для этого этапа).
- Чистота calculator: импорты пакета — только `java.*` и `common.Money`; в `InvalidCalculationException` добавлен как наследник IAE для совместимости тестов, других изменений ядра нет.

## Ограничения и дисциплина

- Реализация коммитов 1–2 НЕ считается независимо проверенной — ревью кода не завершено.
- `frontend/nginx.conf` (`~ ^/(api|oauth2|login/oauth2)/`) и vite-прокси `/api` уже покрывают `/api/calc` — изменений не потребовалось.

## Открытые вопросы / требуют решения

1. **Хэш canonical-строки.** Формат канонизации `ParamsHasher` (поля через `|`, порядок досрочных по компаратору type/month/startMonth/...) зафиксирован кодом; если когда-нибудь поменяете канонизацию, старые документы в Mongo получат «чужие» paramsHash — менять только с миграцией. Менять канонизацию нельзя и из-за Redis-ключей: после TTL 1 ч ключи перезапишутся, но в пределах часа возможны «чужие» попадания кэша.
2. **`CalculationDocument` переиспользует records calculator** (`LoanParams`, `LoanSummary`, `ScheduleRow`, `Savings`). Компактный конструктор `LoanParams` валидирует при десериализации — валидно для сохранённых значений, но связь модель ↔ документ стоит иметь в виду при рефакторинге ядра.
3. **Redis hit быстрее Mongo-only.** Прогрев при Mongo hit делает кэш рабочим и при Redis-first порядке; в пределах TTL Redis закрывает и Mongo-lookup, и пересчёт. Обратная ситуация (Redis hit, документа в Mongo нет) обработана evict + пересчёт.
4. Коммит 3 продолжается: `POST /api/calc/stress` и rate limit (аноним 30/мин, пользователь 120/мин, 429 + Retry-After, лимиты в application.yml) — по отдельному подтверждению. Аутентификация (коммиты auth/user) определит, где появятся `userId` в документах.

## Следующий этап

`POST /api/calc/stress` + rate limit (остаток коммита 3). Redis-кэш реализован и протестирован. Начинать только после подтверждения.
