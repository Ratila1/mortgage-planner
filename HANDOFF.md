# HANDOFF.md — передача состояния

## Общее

- Ветка: `backend_java25` (база — `dev`, коммит `eeaa5a3`).
- Последние коммиты: `1bb570b` — расчётное ядро; `263802b` — перевод промежуточных делений на `Money.MC`; далее коммит REST API + MongoDB (см. ниже).
- Статус: коммиты 1–2 из 5 завершены. Коммит 3 (stress + Redis) **не начат** — ожидается подтверждение ревью.

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

## Проверки

- `gradlew test --rerun-tasks` — BUILD SUCCESSFUL, 41 тест: 18 прежних (`MortgageCalculatorTest` 17 + `MoneyTest` 1) + 23 новых: `ParamsHasherTest` 4, `CalculatorServiceTest` 7 (мок репозитория), `CalculatorControllerTest` 7 (`@WebMvcTest`, контракт: деньги строками, все коды ошибок), `GlobalExceptionHandlerTest` 5 (все ветки, включая 500 для IAE и прочих непойманных).
- Тестов Testcontainers/@DataMongoTest сознательно нет — решено ограничиться юнит/@WebMvcTest.
- Чистота calculator: импорты пакета — только `java.*` и `common.Money`; в `InvalidCalculationException` добавлен как наследник IAE для совместимости тестов, других изменений ядра нет.

## Ограничения и дисциплина

- Реализация коммитов 1–2 НЕ считается независимо проверенной — ревью кода не завершено.
- `frontend/nginx.conf` (`~ ^/(api|oauth2|login/oauth2)/`) и vite-прокси `/api` уже покрывают `/api/calc` — изменений не потребовалось.

## Открытые вопросы / требуют решения

1. **Хэш canonical-строки.** Формат канонизации `ParamsHasher` (поля через `|`, порядок досрочных по компаратору type/month/startMonth/...) зафиксирован кодом; если когда-нибудь поменяете канонизацию, старые документы в Mongo получат «чужие» paramsHash — менять только с миграцией.
2. **`CalculationDocument` переиспользует records calculator** (`LoanParams`, `LoanSummary`, `ScheduleRow`, `Savings`). Компактный конструктор `LoanParams` валидирует при десериализации — валидно для сохранённых значений, но связь модель ↔ документ стоит иметь в виду при рефакторинге ядра.
3. Идти к коммиту 3 по плану: `POST /api/calc/stress` (+), Redis-кэш `calc:{sha256}` TTL 1 ч, rate limit (аноним 30/мин, пользователь 120/мин, 429 + Retry-After, лимиты в application.yml). Аутентификация (коммиты auth/user) определит, где появятся `userId` в документах.

## Следующий этап

Redis-кэш + rate limit + `POST /api/calc/stress` (коммит 3). Начинать только после подтверждения.
