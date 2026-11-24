# credit-bureau — кредитное бюро для банков-партнёров

Отдельный Spring Boot сервис: по телефону и/или ФИО заёмщика отдаёт банку его кредитную историю
(займы ACTIVE/REPAID: статус, сумма, даты). Требования и решения — в [CREDIT_BUREAU.md](../CREDIT_BUREAU.md).

Цепочка: база монолита (VIEW `bureau_applicant_lookup`, только чтение) → `user_id` → Mongo `credit_history`.

- Снаружи — только через nginx: `http://localhost:8080/bureau/...` (лимит 10 запросов/с на IP).
- Swagger UI: http://localhost:8080/bureau/swagger-ui.html (кнопка Authorize → ключ в `X-Api-Key`).
- Хранилища: своя БД `nosurprise_bureau` (провайдеры, ключи, журнал), БД монолита только на чтение,
  Mongo только на чтение, Redis (лимиты, идемпотентность).

## Настройка (.env)

| Переменная | Зачем |
|---|---|
| `BUREAU_ADMIN_KEY` | ключ админского API (`X-Admin-Key`); пусто — админский API закрыт |
| `BUREAU_SANDBOX_KEY` | ключ песочницы, создаётся при первом старте: `nsb_sandbox1_<43 символа base64url>` |
| `LOOKUP_DB_USER` / `LOOKUP_DB_PASSWORD` | пользователь для чтения VIEW монолита; пусто — `POSTGRES_USER` |

## Запуск

`docker compose up -d --build credit-bureau frontend` — поднимет и `db-init`, который создаст БД
`nosurprise_bureau`, если её нет. Таблицы создаёт Flyway бюро при старте.

Postman: импортировать [postman/credit-bureau.postman_collection.json](postman/credit-bureau.postman_collection.json),
заполнить переменные `adminKey` и `sandboxKey`. Коллекция проходит весь путь клиента (регистрация → Mailpit →
профиль → телефон → паспорт → заявка → скоринг → одобрение → договор → подпись → погашение, отказ, отмена,
чёрный список → автоотказ) и проверяет, что бюро видит займ. Шаги B04 и E04 ждут SMS-код в переменной `smsCode`: при `SMS_PROVIDER=log` он только в
`docker compose logs backend | grep "SMS stub"`. Для B05 укажите папку `postman/` как Working directory в
настройках Postman (там лежит `passport.png`) или выберите файл вручную.

## Подключить банк

```bash
ADMIN="X-Admin-Key: $BUREAU_ADMIN_KEY"
# 1. провайдер (лимиты по умолчанию 60/мин и 5000/сутки)
curl -s -H "$ADMIN" -H 'Content-Type: application/json' \
     -d '{"name":"Банк Пример","sandbox":false}' http://localhost:8080/bureau/api/admin/providers
# 2. ключ — показывается один раз, передать банку
curl -s -H "$ADMIN" -X POST http://localhost:8080/bureau/api/admin/providers/<id>/keys
# приостановить / возобновить / отозвать ключ
curl -s -H "$ADMIN" -X POST http://localhost:8080/bureau/api/admin/providers/<id>/suspend
curl -s -H "$ADMIN" -X POST http://localhost:8080/bureau/api/admin/providers/<id>/keys/<keyId>/revoke
# журнал за период (для счёта: total и billable)
curl -s -H "$ADMIN" "http://localhost:8080/bureau/api/admin/providers/<id>/requests?from=2026-11-01T00:00:00Z&to=2026-12-01T00:00:00Z"
```

## Запрос банка

```bash
curl -s -H "X-Api-Key: nsb_..." -H 'Content-Type: application/json' -H 'Idempotency-Key: req-123' \
     -d '{"phone":"+375291234567","lastName":"Иванов","firstName":"Иван"}' \
     http://localhost:8080/bureau/api/v1/credit-reports
```

Оба параметра — совпасть должны оба; один — ищем по нему. Несколько заёмщиков под параметры → `409 AMBIGUOUS_SUBJECT`.

## Сборка и тесты

```bash
cd credit-bureau
gradle test              # юнит-тесты, без Docker
gradle integrationTest   # Testcontainers: Postgres, Mongo, Redis — нужен Docker
```
