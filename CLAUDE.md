# CLAUDE.md — mortgage-planner («ООО «Тмыв денег» — ипотека без сюрпризов»)

Перед любым изменением прочитай правила и контракт:

@INSTRUCTION.md

История бэкенда и принятые решения — в HANDOFF.md (длинный, читай по необходимости).

## Структура
- `backend/` — Spring Boot 4.0.9, Java 25, Gradle (Groovy DSL), модульный монолит `dev.nosurprise.*`.
  PostgreSQL + Flyway (`ddl-auto: validate`), MongoDB, Redis, RabbitMQ, Mailpit.
- `frontend/` — React 19 + Vite + TypeScript, Tailwind 4, React Router 7, Recharts.
  Все запросы только через `frontend/src/api/` (типы `types.ts`, клиент `http.ts`, моки `mock.ts`).
- `docker-compose.yml` — всё окружение; наружу только nginx фронта на :8080.

## Команды
- Всё целиком: `docker compose up --build` (сайт http://localhost:8080, письма http://localhost:8025).
  Чистый старт: `docker compose down -v` перед этим. Логи бэка: `docker compose logs backend --tail 200`.
- Бэкенд: `cd backend && gradle build` (тесты на Testcontainers — нужен запущенный Docker), `gradle test --tests '<Класс>'`.
- Фронт: `cd frontend && npm ci && npm test && npm run build`; разработка — `npm run dev` (proxy /api → localhost:8080),
  без бэкенда — `VITE_USE_MOCKS=true npm run dev`.

## Правила
- Деньги — только `BigDecimal`/строки в JSON, округление явное (INSTRUCTION §5).
- Меняешь контракт API — правь DTO бэка и `frontend/src/api/types.ts` + `http.ts` в одном изменении.
- Схема БД — только новой миграцией `V{N}__*.sql`, применённые не трогать.
- Spring Boot 4 = Jackson 3 (`tools.jackson.*`), не `com.fasterxml.jackson.databind`.
- У бинов с несколькими конструкторами — `@Autowired` на нужном; имя `@Bean`-метода не должно совпадать с именем класса-бина.
- `jsonb`-колонки в JPA: `@JdbcTypeCode(SqlTypes.JSON)`.
- После правок прогоняй тесты затронутого модуля; если запуск невозможен — так и скажи.
- Не коммить `.env`. Репозиторий на Windows: не превращай все файлы в CRLF-диффы, коммить только изменённое.
- Ветки: работа в `feature/*` от `dev`; PR в `main` только из `dev` (CI-проверка).

## Известные открытые проблемы
- Ссылка подтверждения почты ведёт на `/api/auth/verify-email` (голый JSON) — должна вести на фронт `/verify-email?token=`.
- OAuth2 success handler отдаёт JSON вместо редиректа на `/`.
- Нет `GET /api/admin/offers` (админка видит только активные предложения).
- `GlobalExceptionHandler` отдаёт 500 на неизвестный путь вместо 404.
