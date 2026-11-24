############################################### # CREDIT_BUREAU.md — кредитное бюро для внешних банков: требования и правила реализации

Инструкция для реализации. Формат и конвенции — как в MICROLOANS.md (§7 там действует и здесь).
Ветка: `feature/Credit_Bureau`. Две части работы:

- **Часть A — монолит** (`backend/`): только VIEW для поиска заявителя и индексы. Mongo и сущности не меняются.
- **Часть B — микросервис** `credit-bureau/` (корень репозитория): отвечает внешним банкам по API-ключу.

---

## 1. Цель и границы

Другой банк (провайдер) с нашего согласия запрашивает кредитную историю заёмщика по телефону и/или ФИО.
Это общение сервер–сервер: интеграция банка ходит в наш API, клиент в процессе не участвует.

Входит:

- поиск заёмщика в Postgres монолита (только чтение) → история из Mongo `credit_history`;
- микросервис с API для провайдеров, ключами, лимитами запросов, журналом и Swagger UI;
- админский API бюро для управления провайдерами и ключами.

Не входит: согласие клиента (решено: не нужно), биллинг (журнал есть — считать по нему потом),
идентификационный номер паспорта (решено: не используем).

## 2. Термины


| Термин                    | Значение                                                                                                                               |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Провайдер              | Внешний банк, которому мы разрешили запрашивать историю                                        |
| API-ключ                    | Секрет провайдера, с которым он ходит в бюро. У нас хранится только хеш               |
| Заёмщик                  | Пользователь, у которого есть хотя бы одна заявка на микрозайм                             |
| Снимок заявителя | ФИО и телефон в`loan_applications.applicant_*` на момент подачи (MICROLOANS.md §3.6)                                |
| Песочница              | Провайдер с флагом`sandbox`: видит только фиктивных заёмщиков, для проб через Swagger |

## 3. Поиск заёмщика

### 3.1 Правило

Банк присылает телефон, ФИО или оба параметра:


| Пришло                | Ищем                                                                |
| --------------------------- | ----------------------------------------------------------------------- |
| телефон и ФИО    | заявки, где совпали**и** телефон, **и** ФИО |
| только телефон | заявки с этим телефоном                             |
| только ФИО         | заявки с этим ФИО                                         |
| ничего                | `400 VALIDATION_FAILED`                                                 |

ФИО = фамилия + имя обязательно, отчество — если прислали (если не прислали, не сравнивается).

### 3.1.1 Какие заявки считаются кредитной историей

Только займы: `ACTIVE` (договор подписан, деньги выданы) и `REPAID` (погашен). Остальные статусы — не займы:
`SUBMITTED`/`APPROVED` — в процессе, `REJECTED` — отказ, `CANCELLED` — отозвал клиент, `EXPIRED` — не подписал.

Фильтр работает в двух местах:

- **в поиске** (VIEW, §4): по неподписанной заявке с чужими данными заёмщика не найти. В подписанных заявках
  телефон, как правило, подтверждён — подпись идёт кодом из SMS на подтверждённый номер;
- **в ответе** (Mongo, §7.2): `credit_history` хранит все статусы, банку уходят только `ACTIVE` и `REPAID`.

Человек, у которого только отказы или незавершённые заявки, для банка — `found: false`.

> Обновление (CREDIT_POLICY.md §10): к займам добавлен `OVERDUE` (просрочен). VIEW (миграция монолита V17)
> и чтение Mongo включают `ACTIVE`, `OVERDUE`, `REPAID`; в займе отчёта — `dueDate`, `paidAt`, `overdueDays`.

### 3.2 Где ищем и почему по снимкам заявок

Ищем в снимках заявителя (`loan_applications.applicant_*`) заявок в статусах `ACTIVE`/`REPAID`, а не в текущем профиле:
кредитная история — это заявки, и в каждой зафиксированы ФИО и телефон, с которыми человек её подавал.
Если клиент потом сменил телефон, банк со старым номером всё равно найдёт займы, взятые с этим номером.

Результат поиска — `user_id`. По нему бюро читает из Mongo **все** займы (`ACTIVE`/`REPAID`) этого клиента,
а не только совпавшие по параметрам: история человека — целиком.

### 3.3 Нормализация (одинаково у банка в запросе и в SQL)

- Телефон: убрать пробелы, скобки, дефисы; формат `^\+\d{10,15}$` (как `ProfileRequest` в монолите).
- ФИО: `trim`, сравнение без учёта регистра (`lower`), `ё` = `е`. Ни подстрок, ни «похожих» — только точное совпадение.

### 3.4 Несколько заёмщиков под одни параметры

Чаще всего по одному ФИО: однофамильцы. Отдавать банку смешанную историю двух людей нельзя.

Решение: если найдено больше одного `user_id` → `409 AMBIGUOUS_SUBJECT`
«найдено несколько заёмщиков, уточните запрос вторым параметром». Такой ответ не пишется в журнал как оплачиваемый
(`billable = false`) и не раскрывает, сколько людей нашлось и кто они.

### 3.5 Почему ФИО и телефон — слабые идентификаторы

ФИО ничем не проверяется, телефон подтверждается кодом только перед подписью договора. На этапе подачи человек
может вписать чужие данные. Поэтому банку рекомендуем присылать оба параметра (это в описании Swagger).

---

## Часть A. Монолит

## 4. VIEW для бюро — миграция `V16__bureau_applicant_lookup.sql`

Бюро не читает таблицы монолита напрямую — только VIEW. VIEW — контракт: монолит может менять таблицы,
пока VIEW отдаёт те же колонки. Поменял VIEW — поправь бюро.

```sql
-- V16: контракт для сервиса credit-bureau (CREDIT_BUREAU.md §4): поиск заёмщика по снимкам заявок.
-- Бюро читает только этот VIEW и только на чтение; таблицы монолита ему не видны.
-- Только займы — ACTIVE и REPAID (§3.1.1): неподписанные заявки с чужими данными в поиск не попадают.
CREATE VIEW bureau_applicant_lookup AS
SELECT a.user_id,
       a.id                                                   AS application_id,
       a.applicant_phone                                      AS phone,
       replace(lower(a.applicant_last_name), 'ё', 'е')        AS last_name_norm,
       replace(lower(a.applicant_first_name), 'ё', 'е')       AS first_name_norm,
       replace(lower(coalesce(a.applicant_middle_name, '')), 'ё', 'е') AS middle_name_norm
FROM loan_applications a
WHERE a.status IN ('ACTIVE', 'REPAID');

-- Поиск по телефону и по ФИО: без индексов это полный проход по всем заявкам на каждый запрос банка.
-- Частичные индексы с тем же условием, что во VIEW: в них только займы, они меньше и Postgres их использует.
CREATE INDEX idx_loan_applications_applicant_phone ON loan_applications (applicant_phone)
    WHERE status IN ('ACTIVE', 'REPAID');
CREATE INDEX idx_loan_applications_applicant_names ON loan_applications
    (replace(lower(applicant_last_name), 'ё', 'е'), replace(lower(applicant_first_name), 'ё', 'е'))
    WHERE status IN ('ACTIVE', 'REPAID');
```

Выражения и условие `WHERE` в индексах и во VIEW должны совпадать символ в символ, иначе Postgres индекс не использует.
Проверить: `EXPLAIN` запроса бюро (§7.2) — должен быть `Index Scan`/`Bitmap Index Scan`.

Больше в монолите ничего не меняется: `credit_history` в Mongo уже содержит `userId` и индекс `userId + submittedAt`.

---

## Часть B. Микросервис `credit-bureau/`

## 5. Устройство

- Отдельное Spring Boot приложение в `credit-bureau/`: свой `build.gradle`, `Dockerfile` — по образцу
  `notification-service/`. Стек как в монолите: Spring Boot 4.0.9, Java 25, Jackson 3. Пакет `dev.nosurprise.bureau`.
  Порт 8091, `server.servlet.context-path=/bureau`.
- Кода монолита не импортирует. Контракты с монолитом — VIEW `bureau_applicant_lookup` и формат документа
  `credit_history` в Mongo (`schemaVersion`).

### 5.1 Хранилища


| Что                                         | Где                                                                     | Доступ                             |
| ---------------------------------------------- | -------------------------------------------------------------------------- | ---------------------------------------- |
| Провайдеры, ключи, журнал | своя БД`nosurprise_bureau` (тот же контейнер Postgres) | чтение/запись, JPA + Flyway  |
| Поиск заёмщика                    | БД монолита`nosurprise`, VIEW `bureau_applicant_lookup`          | только чтение,`JdbcTemplate` |
| Кредитная история              | Mongo`nosurprise.credit_history`                                           | только чтение                |
| Лимиты, идемпотентность   | Redis (общий контейнер, префикс`bureau:`)             | чтение/запись                |

### 5.2 Два DataSource

- **основной** — `spring.datasource.*` → `nosurprise_bureau`, обычная автонастройка Spring Boot; на нём JPA и Flyway;
- **чтение монолита** — `app.lookup.*` (`LOOKUP_DB_URL/USER/PASSWORD`) → `nosurprise`.

Реализация: второй пул — не DataSource-бин, а `HikariDataSource` внутри компонента `ApplicantLookup`
(свой `JdbcTemplate`, закрывается при остановке). Так автонастройка Spring Boot остаётся на базе бюро и не
путается в двух DataSource: ни `@Primary`, ни ручной настройки JPA не нужно.

Настройки пула чтения: `maximum-pool-size: 5`, `read-only: true` (Postgres отклонит любую запись даже при баге),
`connection-timeout: 3s`. Пул маленький: монолит и бюро делят один сервер Postgres, бюро не должно отъедать соединения.

Одновременная работа монолита и бюро с одним Postgres — штатная ситуация: у каждого свой пул, чтение не
блокирует запись (MVCC), бюро видит только закоммиченные данные.

Пользователь БД: локально — тот же `nosurprise` (защита от записи — `read-only` пула). В проде — отдельная роль:

```sql
CREATE ROLE bureau_reader LOGIN PASSWORD '<из секрета>';
GRANT CONNECT ON DATABASE nosurprise TO bureau_reader;
GRANT USAGE ON SCHEMA public TO bureau_reader;
GRANT SELECT ON bureau_applicant_lookup TO bureau_reader;   -- только VIEW, таблицы не видны
```

Роль не создаётся миграцией: в миграциях не храним пароли (INSTRUCTION.md §6.1).

## 6. Провайдеры и ключи

### 6.1 Таблицы (`V1__providers.sql` бюро)

```sql
CREATE TABLE providers (
    id                  uuid PRIMARY KEY,
    name                varchar(200) NOT NULL UNIQUE,
    status              varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    sandbox             boolean      NOT NULL DEFAULT false,
    requests_per_minute integer      NOT NULL CHECK (requests_per_minute > 0),
    requests_per_day    integer      NOT NULL CHECK (requests_per_day > 0),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    version             bigint       NOT NULL DEFAULT 0
);

-- Несколько ключей на провайдера: ротация без простоя (выдали новый, банк переключился, старый отозвали).
CREATE TABLE provider_keys (
    id           uuid PRIMARY KEY,
    provider_id  uuid        NOT NULL REFERENCES providers (id),
    key_prefix   varchar(16) NOT NULL UNIQUE,
    key_hash     varchar(64) NOT NULL,          -- sha256(секретная часть), hex
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);

-- Журнал запросов: кто, по каким параметрам, что ответили. По нему считаются деньги.
CREATE TABLE report_requests (
    id              uuid PRIMARY KEY,
    provider_id     uuid        NOT NULL REFERENCES providers (id),
    key_id          uuid        NOT NULL REFERENCES provider_keys (id),
    search_by       varchar(16) NOT NULL CHECK (search_by IN ('PHONE', 'NAME', 'PHONE_AND_NAME')),
    query_hash      varchar(64) NOT NULL,       -- sha256 нормализованных параметров, не сами ФИО/телефон
    result          varchar(16) NOT NULL CHECK (result IN ('FOUND', 'NOT_FOUND', 'AMBIGUOUS')),
    billable        boolean     NOT NULL,
    idempotency_key varchar(100),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_report_requests_idempotency ON report_requests (provider_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_report_requests_provider_created ON report_requests (provider_id, created_at);
```

ФИО и телефон в журнал не пишутся — только хеш параметров (для разбора споров: банк присылает те же параметры,
мы сравниваем хеш).

### 6.2 Формат ключа

`nsb_prefix_secret`: `prefix` — 8 символов `[a-z0-9]`, по нему ищется строка; `secret` — 32 байта
`SecureRandom` в base64url (43 символа). Хранится только `sha256(secret)`. Медленный хеш (bcrypt) не нужен:
секрет случайный и длинный, а sha256 не тормозит каждый запрос. Сравнение — `MessageDigest.isEqual`.
Ключ показывается один раз — в ответе на создание.

### 6.3 Аутентификация

Spring Security, stateless. Фильтр читает `X-Api-Key`:


| Ситуация                                                                                                                        | Ответ                                                                                      |
| --------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| Заголовка нет, неверный формат, ключ не найден, хеш не совпал, ключ отозван | `401 INVALID_API_KEY` (одинаково — не подсказываем, что не так) |
| Провайдер`SUSPENDED`                                                                                                           | `403 PROVIDER_SUSPENDED`                                                                        |
| Всё в порядке                                                                                                                | в контексте`ProviderPrincipal(providerId, keyId, sandbox, лимиты)`              |

`last_used_at` обновлять не чаще раза в минуту на ключ (флаг в Redis).

## 7. API для провайдеров

### 7.1 `POST /bureau/api/v1/credit-reports`

Параметры — в теле, а не в URL: не попадают в access-логи и историю прокси.

```json
{ "phone": "+375291234567", "lastName": "Иванов", "firstName": "Иван", "middleName": "Иванович" }
```

Все поля необязательные, но нужен телефон и/или пара «фамилия + имя». Отчество без фамилии и имени — 400.
Заголовки: `X-Api-Key` (обязательно), `Idempotency-Key` (необязательно).

Ответ `200` — заёмщик найден:

```json
{
  "reportId": "uuid",
  "found": true,
  "matchedBy": "PHONE_AND_NAME",
  "totalLoans": 3,
  "loansByStatus": { "REPAID": 2, "ACTIVE": 1 },
  "loans": [
    { "status": "ACTIVE", "amount": "500.00", "currency": "BYN",
      "submittedAt": "...", "decidedAt": "...", "closedAt": null }
  ],
  "generatedAt": "..."
}
```

Ответ `200` — не найден: `{ "reportId": "uuid", "found": false, "generatedAt": "..." }`. Пишется в журнал,
`billable = true`: это тоже ответ на запрос.

Ответ `409 AMBIGUOUS_SUBJECT` — найдено больше одного заёмщика (§3.4).

Наружу уходят только эти поля: количество займов, количество по статусам (`ACTIVE`, `REPAID`), по каждому займу —
статус, сумма и даты.
Не отдаём: userId, ФИО и телефон (банк их прислал сам — эхо не нужно; если прислал только телефон, ФИО раскрывать
нельзя), зарплату, ПДН, проценты, тип займа, комментарии и id админа, хронологию и проколы.

### 7.2 Обработка

1. Валидация и нормализация параметров (§3.3) → `400 VALIDATION_FAILED`.
2. Идемпотентность: тот же `Idempotency-Key` и тот же хеш параметров → сохранённый ответ из Redis (24 ч),
   без лимита и без новой строки журнала. Тот же ключ с другими параметрами → `422 IDEMPOTENCY_KEY_REUSED`.
3. Лимит провайдера (§8).
4. Песочница → ответ из `SandboxReports`, шаги 5–6 пропускаются.
5. Postgres монолита, один запрос под режим поиска (`ApplicantLookup`):
   ```sql
   -- PHONE
   SELECT DISTINCT user_id FROM bureau_applicant_lookup WHERE phone = ? LIMIT 2
   -- NAME (+ AND middle_name_norm = ?, если прислали отчество)
   SELECT DISTINCT user_id FROM bureau_applicant_lookup WHERE last_name_norm = ? AND first_name_norm = ? LIMIT 2
   -- PHONE_AND_NAME
   SELECT DISTINCT user_id FROM bureau_applicant_lookup
   WHERE phone = ? AND last_name_norm = ? AND first_name_norm = ? LIMIT 2
   ```
   Реализация: три запроса вместо одного с `(? IS NULL OR ...)` — у подготовленного запроса Postgres со временем
   переходит на общий план, и с OR по параметрам перестаёт использовать индексы.

   `LIMIT 2` — больше и не нужно: 0 → не найден, 1 → найден, 2 → неоднозначно.
6. Mongo: `credit_history.find({ userId: X, status: { $in: ["ACTIVE", "REPAID"] } })` с проекцией
   `status, loan.amount, submittedAt, decidedAt, closedAt`, сортировка `submittedAt`. Индекс `userId + status` уже есть.
   Копия в Mongo отстаёт на секунды: заём, подписанный только что, может появиться в ответе чуть позже.
7. Журнал → ответ (и сохранить его для идемпотентности).

### 7.3 Песочница

Провайдер с `sandbox = true` не ходит ни в Postgres, ни в Mongo — отвечает из фиксированного набора фиктивных
заёмщиков (`SandboxReports`): например, телефон `+375290000001` — платёжеспособный, `+375290000002` — рискованный,
ФИО `Тестов Тест` — неоднозначный (409). Набор перечислен в описании Swagger. Боевые данные песочнице недоступны.

### 7.4 Ошибки

RFC 7807 с полем `code`, как в монолите: `INVALID_API_KEY` 401, `PROVIDER_SUSPENDED` 403, `RATE_LIMITED` 429,
`TOO_MANY_AUTH_FAILURES` 429, `VALIDATION_FAILED` 400, `AMBIGUOUS_SUBJECT` 409, `IDEMPOTENCY_KEY_REUSED` 422,
`SERVICE_UNAVAILABLE` 503 (Postgres монолита, Mongo или Redis недоступны).

### 7.5 Swagger

`springdoc-openapi-starter-webmvc-ui`, линейка 3.x (под Spring Boot 4; версию проверить при первой сборке).

- UI: `/bureau/swagger-ui.html`, спецификация: `/bureau/v3/api-docs`.
- Схема безопасности `apiKey` в заголовке `X-Api-Key` — кнопка Authorize, запросы прямо из браузера.
- Две группы: `provider` (публичная) и `admin` (§9).
- В описании: правило поиска (§3.1), совет присылать оба параметра, данные песочницы.

## 8. Защита от лишних запросов

1. **nginx**: `limit_req` по IP на `/bureau/` — 10 r/s, burst 20. Отсекает мусор до приложения.
2. **Неудачная аутентификация** — счётчик по IP в Redis `bureau:authfail:{ip}`: 10 неудач за 10 минут → `429`
   на 10 минут. Защита от перебора ключей.
3. **Лимит провайдера** — фиксированные окна в Redis, атомарно одним Lua-скриптом (`INCR` + `EXPIRE`):
   `bureau:rl:{providerId}:m:{минута}` и `bureau:rl:{providerId}:d:{дата}`. Превышение → `429 RATE_LIMITED`
   с `Retry-After`. В ответах `X-RateLimit-Limit`, `X-RateLimit-Remaining`. Проверяется до запросов в базы.

По умолчанию: 60 в минуту и 5000 в сутки; песочница — 10 и 100.
Redis, Postgres монолита или Mongo недоступны → `503` (fail closed: бюро отдаёт персональные данные).

## 9. Админский API бюро

`/bureau/api/admin/**`, заголовок `X-Admin-Key` = `BUREAU_ADMIN_KEY` из env. Пусто — админский API закрыт.

- `GET/POST /providers`, `PUT /providers/{id}` (с `version`), `POST /providers/{id}/suspend|activate`;
- `POST /providers/{id}/keys` → ключ в открытом виде, один раз; `POST /providers/{id}/keys/{keyId}/revoke`;
- `GET /providers/{id}/requests?from&to` → журнал и количество оплачиваемых запросов за период.

При первом старте, если провайдеров нет и задан `BUREAU_SANDBOX_KEY`, создаётся песочница с этим ключом.

## 10. Инфраструктура

- **БД бюро**: одноразовый сервис `db-init` в docker-compose (образ postgres) при каждом `up` создаёт
  `nosurprise_bureau` (и `nosurprise_notifications`), если их нет; `credit-bureau` ждёт его завершения. Реализация: не `postgres-init` — тот
  срабатывает только на пустом volume. Таблицы создаёт Flyway бюро (`V1__providers.sql`).
- **docker-compose**: сервис `credit-bureau` (`expose: ["8091"]`, наружу только через nginx). Env:
  `BUREAU_DB_URL/USER/PASSWORD` (своя БД), `LOOKUP_DB_URL/USER/PASSWORD` (БД монолита, только чтение),
  `MONGODB_URI`, `REDIS_HOST`, `REDIS_PASSWORD`, `BUREAU_ADMIN_KEY`, `BUREAU_SANDBOX_KEY`.
  `frontend` зависит от `credit-bureau` (`service_started`): nginx резолвит upstream при старте.
- **nginx** (`frontend/nginx.conf`): `location /bureau/` → `http://credit-bureau:8091` с `limit_req`.
  В `vite.config.ts` — proxy `/bureau` (INSTRUCTION.md §3: каждый новый публичный путь).
- Секреты — в `.env.example` пустыми, значения только в `.env`.

## 11. Безопасность

- ФИО и телефон: в журнале — только хеш параметров; в логах — нигде; в ответе — не возвращаются.
- Ключи провайдеров: только хеш; показ один раз; отзыв и приостановка действуют сразу (проверка на каждом запросе).
- Доступ к БД монолита: только VIEW, только чтение (пул `read-only`, в проде — роль с `SELECT` на VIEW).
- Fail closed: нет Redis, Postgres монолита или Mongo — `503`, а не ответ без проверок.

## 12. Тесты

Монолит: миграция V16 на Testcontainers; `EXPLAIN` запроса из §7.2 использует индексы; заявки не в `ACTIVE`/`REPAID`
во VIEW не попадают.

Бюро:

- юнит: формат ключа и хеш; нормализация телефона и ФИО (регистр, `ё`, пробелы); маппинг документа Mongo в ответ
  (лишние поля не утекают); выбор режима поиска по набору параметров;
- интеграция (Testcontainers: два Postgres — бюро и «монолит» с VIEW, Mongo, Redis): поиск по телефону, по ФИО,
  по обоим; однофамильцы → 409; не найден → 200 `found:false`; только отказы/отмены → `found:false`;
  в ответе нет займов других статусов; 401/403/429; идемпотентность; песочница не видит
  боевых данных; отозванный ключ сразу перестаёт работать; `503` при недоступном Postgres монолита.

## 13. Порядок реализации

1. Монолит: V16 (VIEW + индексы).
2. Бюро: каркас, два DataSource, своя БД + Flyway, провайдеры и ключи, админский API, песочница.
3. Бюро: фильтр ключа, лимиты, поиск (Postgres → Mongo), журнал, идемпотентность.
4. Swagger, nginx, docker-compose (`db-init`), `.env.example`, README.
5. Тесты.

## 14. Зафиксированные решения

1. Поиск по телефону и/или ФИО: пришли оба — совпадать должны оба; пришёл один — ищем по нему.
2. Цепочка: Postgres монолита (VIEW по снимкам заявок) → `user_id` → Mongo `credit_history` по `userId`.
3. Монолит меняется только миграцией V16 (VIEW + индексы); Mongo не меняется.
4. Бюро — отдельный сервис `credit-bureau/`; своя БД `nosurprise_bureau` для провайдеров, ключей и журнала.
5. Банку отдаём: количество займов, количество по статусам, по каждому займу — статус, сумму и даты.
   Кредитная история — только займы `ACTIVE` и `REPAID`: и в поиске, и в ответе.
6. Заёмщик не найден → `200` с `found: false`, запрос оплачиваемый.
7. Согласия клиента нет: доступ определяется таблицей провайдеров.
8. Аутентификация провайдера — API-ключ в `X-Api-Key`, без OAuth/JWT.
9. Несколько заёмщиков под параметры → `409 AMBIGUOUS_SUBJECT`, не оплачивается (§3.4).
10. Провайдеров и ключи заводит админ через админский API бюро (`BUREAU_ADMIN_KEY`), без UI.
11. Лимиты по умолчанию: 60/мин и 5000/сутки, песочница — 10/мин и 100/сутки.
12. Ретраев при расхождении Postgres и Mongo нет: копия отстаёт на секунды, а займ, подписанный только что,
    редко сразу проверяют в другом банке. Отставание после DLQ догоняет сверка монолита (MICROLOANS.md §4.9).
